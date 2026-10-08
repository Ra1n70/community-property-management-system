package com.cpms.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.enums.IntakeSource;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.repository.LockerCellRepository;
import com.cpms.community.locker.repository.LockerRepository;
import com.cpms.community.locker.repository.ParcelRepository;
import com.cpms.community.maintenance.MaintenanceRepository;
import com.cpms.community.maintenance.MaintenanceTicket;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Maintenance and Packages pages load one filtered page at a time instead of every record. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:listpaging;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ListPagingTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired MaintenanceRepository tickets;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;
    @Autowired ParcelRepository parcels;
    @Autowired EntityManagerFactory entities;

    String community;
    Account manager, maya, omar, luis, deliveryCompany;
    final Instant start = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeEach
    void setup() {
        community = "Harbor View " + UUID.randomUUID();
        manager = account("Dana Brooks", Account.Role.MANAGER, community);
        maya = account("Maya Chen", Account.Role.RESIDENT, community);
        omar = account("Omar Haddad", Account.Role.RESIDENT, community);
        luis = account("Luis Ortega", Account.Role.PROVIDER, community);
        luis.providerType = Account.ProviderType.MAINTENANCE;
        accounts.saveAndFlush(luis);
        deliveryCompany = account("Coastal Express", Account.Role.PROVIDER, community);
        deliveryCompany.providerType = Account.ProviderType.DELIVERY;
        accounts.saveAndFlush(deliveryCompany);
    }

    Account account(String name, Account.Role role, String community) {
        Account a = new Account();
        a.email = UUID.randomUUID() + "@harborview.example";
        a.name = name; a.passwordHash = "x"; a.community = community; a.room = "1204";
        a.role = role; a.status = Account.Status.APPROVED;
        return accounts.saveAndFlush(a);
    }

    MaintenanceTicket ticket(Account resident, int day, String category, MaintenanceTicket.Priority priority, Account assignee) {
        MaintenanceTicket t = new MaintenanceTicket();
        t.ticketNo = "TK-" + UUID.randomUUID(); t.community = resident.community;
        t.residentId = resident.id; t.residentName = resident.name; t.room = resident.room;
        t.category = category; t.location = "Kitchen"; t.description = "Sink drains slowly";
        t.priority = priority;
        if (assignee != null) { t.assigneeId = assignee.id; t.assigneeName = assignee.name; t.status = MaintenanceTicket.Status.ACCEPTED; }
        t.createdAt = start.plus(day, ChronoUnit.DAYS); t.updatedAt = t.createdAt;
        return tickets.saveAndFlush(t);
    }

    JsonNode call(Account a, String url, int expected) throws Exception {
        String body = mvc.perform(get(url).with(user(a.email).roles(a.role.name())))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    long statements(Runnable work) {
        var stats = entities.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        work.run();
        return stats.getPrepareStatementCount();
    }

    @Test
    void maintenancePagesFilterOnTheServerAndKeepUrgentRequestsFirst() throws Exception {
        List<MaintenanceTicket> made = new ArrayList<>();
        for (int day = 0; day < 42; day++)
            made.add(ticket(day % 2 == 0 ? maya : omar, day, day % 3 == 0 ? "Electrical" : "Plumbing",
                    MaintenanceTicket.Priority.NORMAL, day % 4 == 0 ? luis : null));
        MaintenanceTicket urgent = ticket(maya, -5, "Appliance", MaintenanceTicket.Priority.URGENT, null);

        JsonNode first = call(manager, "/api/maintenance/page?size=20", 200);
        assertThat(first.get("total").asLong()).isEqualTo(43);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("items")).hasSize(20);
        // The oldest request is urgent, so it still comes first; the rest are newest first.
        assertThat(ids(first).get(0)).isEqualTo(urgent.id);
        assertThat(ids(first).get(1)).isEqualTo(made.get(41).id);
        assertThat(call(manager, "/api/maintenance/page?size=20&page=2", 200).get("items")).hasSize(3);
        assertThat(first.get("categories")).extracting(JsonNode::asText).containsExactly("Appliance", "Electrical", "Plumbing");
        assertThat(first.get("assignees")).hasSize(1);
        assertThat(first.get("assignees").get(0).get("name").asText()).isEqualTo("Luis Ortega");

        assertThat(call(manager, "/api/maintenance/page?priority=URGENT", 200).get("total").asLong()).isEqualTo(1);
        assertThat(call(manager, "/api/maintenance/page?category=Electrical", 200).get("total").asLong()).isEqualTo(14);
        assertThat(call(manager, "/api/maintenance/page?assigneeId=" + luis.id, 200).get("total").asLong()).isEqualTo(11);
        assertThat(call(manager, "/api/maintenance/page?unassigned=true", 200).get("total").asLong()).isEqualTo(32);
        assertThat(call(manager, "/api/maintenance/page?status=ACCEPTED", 200).get("total").asLong()).isEqualTo(11);
        // Days 10..19 inclusive: "to" is the start of the day after the last day shown.
        String range = "from=" + start.plus(10, ChronoUnit.DAYS).minusSeconds(3600) + "&to=" + start.plus(19, ChronoUnit.DAYS).plusSeconds(3600);
        assertThat(call(manager, "/api/maintenance/page?" + range, 200).get("total").asLong()).isEqualTo(10);

        call(manager, "/api/maintenance/page?size=101", 400);
        call(manager, "/api/maintenance/page?page=-1", 400);
        call(manager, "/api/maintenance/page?from=" + start + "&to=" + start, 400);

        // A page costs the same number of statements however many requests exist.
        long small = statements(() -> { try { call(manager, "/api/maintenance/page?size=5", 200); } catch (Exception e) { throw new RuntimeException(e); } });
        long large = statements(() -> { try { call(manager, "/api/maintenance/page?size=5&category=Plumbing", 200); } catch (Exception e) { throw new RuntimeException(e); } });
        assertThat(large).isEqualTo(small);
    }

    @Test
    void residentsAndWorkersPageOnlyTheirOwnRequests() throws Exception {
        for (int day = 0; day < 25; day++) ticket(maya, day, "Plumbing", MaintenanceTicket.Priority.NORMAL, day < 3 ? luis : null);
        MaintenanceTicket omars = ticket(omar, 30, "Electrical", MaintenanceTicket.Priority.URGENT, null);
        Account elsewhere = account("Priya Nair", Account.Role.MANAGER, "Elm Court " + UUID.randomUUID());

        JsonNode mine = call(maya, "/api/maintenance/page", 200);
        assertThat(mine.get("total").asLong()).isEqualTo(25);
        assertThat(mine.get("items")).hasSize(20);
        assertThat(mine.get("categories")).isEmpty();
        // Manager-only filters do not widen or narrow a resident's view.
        assertThat(call(maya, "/api/maintenance/page?priority=URGENT", 200).get("total").asLong()).isEqualTo(25);
        assertThat(call(luis, "/api/maintenance/page", 200).get("total").asLong()).isEqualTo(3);
        assertThat(call(elsewhere, "/api/maintenance/page", 200).get("total").asLong()).isZero();

        // Opening one request by id (from Search) follows the same access rules.
        assertThat(call(omar, "/api/maintenance/" + omars.id, 200).get("id").asLong()).isEqualTo(omars.id);
        call(manager, "/api/maintenance/" + omars.id, 200);
        call(maya, "/api/maintenance/" + omars.id, 404);
        call(luis, "/api/maintenance/" + omars.id, 404);
        call(elsewhere, "/api/maintenance/" + omars.id, 404);
    }

    Parcel parcel(Account resident, Locker locker, ParcelStatus status, int day, Account company) {
        LockerCell cell = new LockerCell();
        cell.locker = locker; cell.cellNumber = "B" + day; cell.size = CellSize.SMALL;
        cell.status = status == ParcelStatus.PENDING_PICKUP ? CellStatus.OCCUPIED : CellStatus.AVAILABLE;
        cell = cells.saveAndFlush(cell);
        Parcel p = new Parcel();
        p.community = resident.community; p.resident = resident; p.cell = cell; p.carrierName = "UPS";
        p.packageSize = CellSize.SMALL; p.status = status;
        p.intakeSource = company == null ? IntakeSource.PROPERTY_STAFF : IntakeSource.CARRIER_SELF_SERVICE;
        if (company != null) { p.registeredBy = company.id; p.courierName = "Sam Rivera"; }
        p.storedAt = start.plus(day, ChronoUnit.DAYS);
        p.expiresAt = p.storedAt.plus(7, ChronoUnit.DAYS);
        return parcels.saveAndFlush(p);
    }

    Locker locker(String community, String location) {
        Locker l = new Locker();
        l.community = community; l.lockerNumber = UUID.randomUUID().toString(); l.location = location;
        return lockers.saveAndFlush(l);
    }

    @Test
    void packagePagesFilterLoadInFewQueriesAndRespectOwnership() throws Exception {
        Locker lobby = locker(community, "Main Lobby"), garage = locker(community, "Garage");
        List<Parcel> made = new ArrayList<>();
        for (int day = 0; day < 30; day++)
            made.add(parcel(day % 2 == 0 ? maya : omar, day < 20 ? lobby : garage,
                    day % 5 == 0 ? ParcelStatus.PICKED_UP : ParcelStatus.PENDING_PICKUP, day, day % 3 == 0 ? deliveryCompany : null));
        Account elsewhere = account("Priya Nair", Account.Role.MANAGER, "Elm Court " + UUID.randomUUID());
        parcel(account("Theo Park", Account.Role.RESIDENT, elsewhere.community), locker(elsewhere.community, "Front Desk"), ParcelStatus.PENDING_PICKUP, 3, null);

        JsonNode first = call(manager, "/api/manager/parcels/page", 200);
        assertThat(first.get("total").asLong()).isEqualTo(30);
        assertThat(first.get("items")).hasSize(20);
        assertThat(ids(first).get(0)).isEqualTo(made.get(29).id);
        assertThat(first.get("items").get(0).get("residentName").asText()).isEqualTo("Omar Haddad");
        assertThat(call(manager, "/api/manager/parcels/page?page=1", 200).get("items")).hasSize(10);
        assertThat(call(manager, "/api/manager/parcels/page?status=PICKED_UP", 200).get("total").asLong()).isEqualTo(6);
        assertThat(call(manager, "/api/manager/parcels/page?lockerId=" + garage.id, 200).get("total").asLong()).isEqualTo(10);
        assertThat(call(manager, "/api/manager/parcels/page?status=PENDING_PICKUP&lockerId=" + lobby.id, 200).get("total").asLong()).isEqualTo(16);
        call(manager, "/api/manager/parcels/page?size=0", 400);

        // Resident, cell and locker come with the page: 20 rows cost no more statements than 2.
        long two = statements(() -> { try { call(manager, "/api/manager/parcels/page?size=2", 200); } catch (Exception e) { throw new RuntimeException(e); } });
        long twenty = statements(() -> { try { call(manager, "/api/manager/parcels/page?size=20", 200); } catch (Exception e) { throw new RuntimeException(e); } });
        assertThat(twenty).isEqualTo(two);

        // A package opened from Search is loaded by id, only inside the manager's community.
        assertThat(call(manager, "/api/manager/parcels/" + made.get(0).id, 200).get("id").asLong()).isEqualTo(made.get(0).id);
        call(elsewhere, "/api/manager/parcels/" + made.get(0).id, 404);
        call(maya, "/api/manager/parcels/" + made.get(0).id, 403);
        assertThat(call(elsewhere, "/api/manager/parcels/page", 200).get("total").asLong()).isEqualTo(1);

        JsonNode mayas = call(maya, "/api/resident/parcels/page?size=10", 200);
        assertThat(mayas.get("total").asLong()).isEqualTo(15);
        assertThat(mayas.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(mayas).get(0)).isEqualTo(made.get(28).id);
        call(manager, "/api/resident/parcels/page", 403);

        JsonNode delivered = call(deliveryCompany, "/api/provider/deliveries/parcels/page?size=4", 200);
        assertThat(delivered.get("total").asLong()).isEqualTo(10);
        assertThat(delivered.get("totalPages").asInt()).isEqualTo(3);
        assertThat(delivered.get("items").get(0).get("courierName").asText()).isEqualTo("Sam Rivera");
        call(luis, "/api/provider/deliveries/parcels/page", 403);
    }
}
