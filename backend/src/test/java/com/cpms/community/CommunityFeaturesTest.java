package com.cpms.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.amenity.entity.AmenityClosure;
import com.cpms.community.amenity.entity.AmenityWeeklyHour;
import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.enums.ReservationStatus;
import com.cpms.community.amenity.repository.AmenityClosureRepository;
import com.cpms.community.amenity.repository.AmenityRepository;
import com.cpms.community.amenity.repository.AmenityWeeklyHourRepository;
import com.cpms.community.amenity.repository.ReservationRepository;
import com.cpms.community.maintenance.MaintenanceRepository;
import com.cpms.community.maintenance.MaintenanceTicket;
import com.cpms.community.payment.Bill;
import com.cpms.community.payment.BillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Community Hub voting, My Account lease documents, Contact & Support and the manager reports. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:communityfeatures;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class CommunityFeaturesTest {
    @TempDir static Path leaseDir;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("leases.upload-dir", () -> leaseDir.toString()); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired MaintenanceRepository tickets;
    @Autowired BillRepository bills;
    @Autowired AmenityRepository amenities;
    @Autowired AmenityWeeklyHourRepository weeklyHours;
    @Autowired AmenityClosureRepository closures;
    @Autowired ReservationRepository reservations;

    Account manager, maya, omar, provider, otherManager, otherResident;

    @BeforeEach
    void setup() {
        String community = "Harbor View " + UUID.randomUUID(), elsewhere = "Elm Court " + UUID.randomUUID();
        manager = account("Dana Brooks", Account.Role.MANAGER, community);
        maya = account("Maya Chen", Account.Role.RESIDENT, community);
        omar = account("Omar Haddad", Account.Role.RESIDENT, community);
        provider = account("Luis Ortega", Account.Role.PROVIDER, community);
        otherManager = account("Priya Nair", Account.Role.MANAGER, elsewhere);
        otherResident = account("Theo Park", Account.Role.RESIDENT, elsewhere);
    }

    Account account(String name, Account.Role role, String community) {
        Account a = new Account();
        a.email = UUID.randomUUID() + "@harborview.example";
        a.name = name; a.passwordHash = "x"; a.community = community; a.room = "1204";
        a.role = role; a.status = Account.Status.APPROVED;
        if (role == Account.Role.PROVIDER) a.providerType = Account.ProviderType.MAINTENANCE;
        return accounts.saveAndFlush(a);
    }

    JsonNode call(Account a, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.with(user(a.email).roles(a.role.name())).with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isBlank() ? json.nullNode() : json.readTree(response);
    }

    Map<String, Object> poll(String title, Instant closesAt, String... options) {
        return Map.of("title", title, "description", "Vote in the lobby or here.", "options", List.of(options), "closesAt", closesAt.toString());
    }

    @Test
    void residentsVoteOnceAndSeeResultsAfterVotingOrClosing() throws Exception {
        Instant nextWeek = Instant.now().plus(Duration.ofDays(7));
        call(maya, post("/api/polls"), poll("Pool hours", nextWeek, "Earlier", "Later"), 403);
        call(manager, post("/api/polls"), poll("Pool hours", nextWeek, "Only one"), 400);
        call(manager, post("/api/polls"), poll("Pool hours", nextWeek, "Earlier", "earlier "), 400);
        call(manager, post("/api/polls"), poll("Pool hours", Instant.now().minusSeconds(60), "Earlier", "Later"), 400);
        JsonNode created = call(manager, post("/api/polls"), poll("Pool hours", nextWeek, "Open at 6 AM", "Open at 8 AM", "Keep as is"), 201);
        long id = created.get("id").asLong();
        long early = created.get("options").get(0).get("id").asLong(), later = created.get("options").get(1).get("id").asLong();
        assertThat(created.get("eligibleVoters").asLong()).isEqualTo(2);

        // Before voting a resident sees the options but not the counts.
        JsonNode before = call(maya, get("/api/polls"), null, 200).get(0);
        assertThat(before.get("resultsVisible").asBoolean()).isFalse();
        assertThat(before.get("options").get(0).get("votes").isNull()).isTrue();

        JsonNode voted = call(maya, post("/api/polls/" + id + "/vote"), Map.of("optionId", early), 200);
        assertThat(voted.get("myOptionId").asLong()).isEqualTo(early);
        assertThat(voted.get("totalVotes").asLong()).isEqualTo(1);
        call(maya, post("/api/polls/" + id + "/vote"), Map.of("optionId", later), 409);
        call(omar, post("/api/polls/" + id + "/vote"), Map.of("optionId", 999999), 400);
        call(manager, post("/api/polls/" + id + "/vote"), Map.of("optionId", early), 403);
        call(provider, get("/api/polls"), null, 403);
        call(otherResident, post("/api/polls/" + id + "/vote"), Map.of("optionId", early), 404);
        assertThat(call(otherManager, get("/api/polls"), null, 200)).isEmpty();
        assertThat(call(omar, get("/api/polls"), null, 200).get(0).get("totalVotes").isNull()).isTrue();
        assertThat(call(manager, get("/api/polls"), null, 200).get(0).get("totalVotes").asLong()).isEqualTo(1);

        call(maya, post("/api/polls/" + id + "/close"), null, 403);
        JsonNode closed = call(manager, post("/api/polls/" + id + "/close"), null, 200);
        assertThat(closed.get("open").asBoolean()).isFalse();
        call(omar, post("/api/polls/" + id + "/vote"), Map.of("optionId", later), 409);
        JsonNode results = call(omar, get("/api/polls"), null, 200).get(0);
        assertThat(results.get("resultsVisible").asBoolean()).isTrue();
        assertThat(results.get("options").get(0).get("votes").asLong()).isEqualTo(1);

        call(otherManager, delete("/api/polls/" + id), null, 404);
        call(manager, delete("/api/polls/" + id), null, 204);
        assertThat(call(maya, get("/api/polls"), null, 200)).isEmpty();
    }

    JsonNode upload(Account by, Account resident, String filename, byte[] content, int expected) throws Exception {
        MockMultipartFile request = new MockMultipartFile("request", "", "application/json",
                json.writeValueAsBytes(Map.of("residentId", resident.id, "title", "Lease agreement 2026-2027", "startsOn", "2026-09-01", "endsOn", "2027-08-31")));
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", content);
        String response = mvc.perform(multipart("/api/lease-documents").file(request).file(file).with(user(by.email).roles(by.role.name())).with(csrf()))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? json.nullNode() : json.readTree(response);
    }

    @Test
    void leaseDocumentsReachOnlyTheirResidentAndTheCommunitysManagers() throws Exception {
        byte[] pdf = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        upload(maya, maya, "lease.pdf", pdf, 403);
        upload(manager, maya, "lease.pdf", "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8), 400);
        upload(manager, otherResident, "lease.pdf", pdf, 400);
        upload(manager, provider, "lease.pdf", pdf, 400);
        JsonNode doc = upload(manager, maya, "C:\\Users\\dana\\Maya \"lease\".pdf", pdf, 201);
        long id = doc.get("id").asLong();
        assertThat(doc.get("filename").asText()).isEqualTo("Maya lease.pdf");
        assertThat(Files.list(leaseDir).count()).isEqualTo(1);

        assertThat(call(maya, get("/api/lease-documents"), null, 200)).hasSize(1);
        assertThat(call(omar, get("/api/lease-documents"), null, 200)).isEmpty();
        // A resident cannot list someone else's documents by passing their id.
        assertThat(call(omar, get("/api/lease-documents?residentId=" + maya.id), null, 200)).isEmpty();
        assertThat(call(manager, get("/api/lease-documents?residentId=" + maya.id), null, 200)).hasSize(1);
        assertThat(call(otherManager, get("/api/lease-documents"), null, 200)).isEmpty();

        var download = mvc.perform(get("/api/lease-documents/" + id + "/file").with(user(maya.email).roles("RESIDENT")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse();
        assertThat(download.getContentAsByteArray()).isEqualTo(pdf);
        assertThat(download.getHeader("Content-Disposition")).startsWith("attachment");
        mvc.perform(get("/api/lease-documents/" + id + "/file").with(user(manager.email).roles("MANAGER"))).andExpect(status().isOk());
        for (Account outsider : List.of(omar, otherManager, otherResident))
            mvc.perform(get("/api/lease-documents/" + id + "/file").with(user(outsider.email).roles(outsider.role.name()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/lease-documents/" + id + "/file").with(user(provider.email).roles("PROVIDER"))).andExpect(status().isForbidden());

        call(maya, delete("/api/lease-documents/" + id), null, 403);
        call(otherManager, delete("/api/lease-documents/" + id), null, 404);
        call(manager, delete("/api/lease-documents/" + id), null, 204);
        assertThat(call(maya, get("/api/lease-documents"), null, 200)).isEmpty();
        assertThat(Files.list(leaseDir).count()).isZero();
    }

    @Test
    void contactDetailsAndFaqAreSharedWithResidentsAndEditedByManagers() throws Exception {
        JsonNode empty = call(maya, get("/api/support"), null, 200);
        assertThat(empty.get("contact").get("officePhone").isNull()).isTrue();
        assertThat(empty.get("faqs")).isEmpty();

        Map<String, Object> contact = new HashMap<>(Map.of("officePhone", "(555) 010-2040", "email", "office@harborview.example", "officeHours", "Mon–Fri 9 AM – 5 PM", "emergencyPhone", "(555) 010-9111"));
        call(maya, put("/api/support/contact"), contact, 403);
        call(manager, put("/api/support/contact"), Map.of("email", "not an email"), 400);
        JsonNode saved = call(manager, put("/api/support/contact"), contact, 200);
        contact.put("version", saved.get("version").asLong());
        contact.put("officeHours", "Mon–Sat 9 AM – 5 PM");
        call(manager, put("/api/support/contact"), contact, 200);
        call(manager, put("/api/support/contact"), contact, 409);

        JsonNode parking = call(manager, post("/api/support/faqs"), Map.of("question", "Where can guests park?", "answer", "Visitor spots are by the north gate."), 201);
        JsonNode gym = call(manager, post("/api/support/faqs"), Map.of("question", "When is the gym open?", "answer", "6 AM to 10 PM every day."), 201);
        call(maya, post("/api/support/faqs"), Map.of("question", "Q", "answer", "A"), 403);
        call(manager, put("/api/support/faqs/order"), Map.of("ids", List.of(gym.get("id").asLong())), 409);
        call(manager, put("/api/support/faqs/order"), Map.of("ids", List.of(gym.get("id").asLong(), parking.get("id").asLong())), 200);

        JsonNode shown = call(maya, get("/api/support"), null, 200);
        assertThat(shown.get("contact").get("officeHours").asText()).isEqualTo("Mon–Sat 9 AM – 5 PM");
        assertThat(shown.get("faqs").get(0).get("question").asText()).isEqualTo("When is the gym open?");
        assertThat(call(otherResident, get("/api/support"), null, 200).get("faqs")).isEmpty();
        call(provider, get("/api/support"), null, 403);

        call(otherManager, delete("/api/support/faqs/" + parking.get("id").asLong()), null, 404);
        call(manager, delete("/api/support/faqs/" + parking.get("id").asLong()), null, 204);
        assertThat(call(maya, get("/api/support"), null, 200).get("faqs")).hasSize(1);
    }

    MaintenanceTicket ticket(Account resident, String category, Instant created, Instant completed) {
        MaintenanceTicket t = new MaintenanceTicket();
        t.ticketNo = "TK-" + UUID.randomUUID(); t.community = resident.community;
        t.residentId = resident.id; t.residentName = resident.name; t.room = resident.room;
        t.category = category; t.location = "Kitchen"; t.description = "Sink drains slowly";
        t.createdAt = created; t.updatedAt = created;
        if (completed != null) { t.status = MaintenanceTicket.Status.COMPLETED; t.completedAt = completed; }
        return tickets.saveAndFlush(t);
    }

    Bill bill(Account resident, String amount, Instant created, LocalDate due, Instant paid) {
        Bill b = new Bill();
        b.community = resident.community; b.residentId = resident.id; b.residentName = resident.name; b.createdBy = manager.id;
        b.title = "Bill " + amount; b.amount = new BigDecimal(amount); b.dueDate = due; b.createdAt = created;
        if (paid != null) { b.status = "PAID"; b.paidAt = paid; b.receiptNumber = "DEMO-" + UUID.randomUUID(); }
        return bills.saveAndFlush(b);
    }

    Map<String, JsonNode> metrics(JsonNode report) {
        Map<String, JsonNode> metrics = new HashMap<>();
        report.get("sections").forEach(s -> s.get("metrics").forEach(m -> metrics.put(s.get("key").asText() + "." + m.get("key").asText(), m.get("value"))));
        return metrics;
    }

    @Test
    void reportsCountTheChosenDatesForTheManagersCommunityAndExportCsv() throws Exception {
        ZoneId zone = ZoneId.of("America/Los_Angeles");
        Instant march3 = LocalDate.of(2026, 3, 3).atTime(10, 0).atZone(zone).toInstant();
        ticket(maya, "Plumbing", march3, march3.plus(Duration.ofHours(30)));
        ticket(omar, "Plumbing", march3.plus(Duration.ofDays(2)), null);
        ticket(omar, "=HYPERLINK(\"x\")", march3.plus(Duration.ofDays(3)), null);
        ticket(maya, "Electrical", march3.plus(Duration.ofDays(40)), null);
        ticket(otherResident, "Plumbing", march3, null);
        // Created in February, completed in March: belongs to February's figures, not March's.
        Instant feb20 = LocalDate.of(2026, 2, 20).atTime(10, 0).atZone(zone).toInstant();
        ticket(omar, "Plumbing", feb20, march3);
        bill(maya, "1850.00", march3, LocalDate.of(2026, 3, 10), null);
        bill(omar, "40.00", march3, LocalDate.of(2026, 3, 20), march3.plus(Duration.ofDays(1)));
        bill(omar, "500.00", feb20, LocalDate.of(2026, 3, 1), march3);

        String march = "?from=2026-03-01&to=2026-03-31";
        call(maya, get("/api/manager/statistics" + march), null, 403);
        call(manager, get("/api/manager/statistics?from=2026-03-31&to=2026-03-01"), null, 400);
        JsonNode report = call(manager, get("/api/manager/statistics" + march), null, 200);
        Map<String, JsonNode> metrics = new HashMap<>();
        report.get("sections").forEach(s -> s.get("metrics").forEach(m -> metrics.put(s.get("key").asText() + "." + m.get("key").asText(), m.get("value"))));
        assertThat(metrics.get("maintenance.submitted").asLong()).isEqualTo(3);
        assertThat(metrics.get("maintenance.completed").asLong()).isEqualTo(1);
        assertThat(metrics.get("maintenance.completionRate").decimalValue()).isEqualByComparingTo("33.3");
        assertThat(metrics.get("maintenance.resolutionHours").asDouble()).isEqualTo(30.0);
        assertThat(metrics.get("maintenance.open").asLong()).isEqualTo(3);
        assertThat(metrics.get("residents.approved").asLong()).isEqualTo(2);
        // Bills created in March: 1850 unpaid and overdue, 40 paid. The February bill paid in March is not counted.
        assertThat(metrics.get("payments.billed").asLong()).isEqualTo(2);
        assertThat(metrics.get("payments.billedAmount").decimalValue()).isEqualByComparingTo("1890.00");
        assertThat(metrics.get("payments.paidAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(metrics.get("payments.overdueAmount").decimalValue()).isEqualByComparingTo("1850.00");
        assertThat(metrics.get("payments.outstanding").decimalValue()).isEqualByComparingTo("1850.00");
        assertThat(metrics.get("payments.overdue").asLong()).isEqualTo(1);
        JsonNode categories = report.get("sections").get(1).get("breakdown");
        assertThat(categories.get(0).get("label").asText()).isEqualTo("Plumbing");
        assertThat(categories.get(0).get("value").asLong()).isEqualTo(2);

        var csv = mvc.perform(get("/api/manager/statistics/export" + march).with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("community-report-2026-03-01-to-2026-03-31.csv")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Maintenance\",\"Requests submitted\",3,\"requests\"");
        // A category that looks like a spreadsheet formula is exported as text.
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        mvc.perform(get("/api/manager/statistics/export" + march).with(user(maya.email).roles("RESIDENT"))).andExpect(status().isForbidden());
    }

    Amenity amenity(String community, String name, int capacity) {
        Amenity a = new Amenity();
        a.community = community; a.name = name; a.type = "Fitness"; a.location = "Lobby";
        a.capacity = capacity; a.slotDurationMinutes = 60;
        return amenities.saveAndFlush(a);
    }

    Reservation reservation(Amenity a, Account resident, Instant start, Instant cancelledAt) {
        Reservation r = new Reservation();
        r.amenityId = a.id; r.accountId = resident.id; r.community = a.community; r.room = resident.room; r.guestName = resident.name;
        r.startAt = start; r.endAt = start.plus(Duration.ofHours(1)); r.slotKey = "report-" + UUID.randomUUID();
        if (cancelledAt != null) { r.status = ReservationStatus.CANCELLED; r.cancelledAt = cancelledAt; r.slotKey = null; }
        return reservations.saveAndFlush(r);
    }

    @Test
    void amenityReportCountsByStartTimeAndUtilizationUsesOpenPlaces() throws Exception {
        ZoneId zone = ZoneId.of("America/Los_Angeles");
        // Two facilities share a name; only the first has hours: Mondays 09:00-11:00, two households per slot.
        Amenity gym = amenity(manager.community, "Gym", 2), annex = amenity(manager.community, "Gym", 1);
        weeklyHours.saveAndFlush(new AmenityWeeklyHour(gym.id, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0)));
        // March 2026 has five Mondays (10 slots); a closure removes the 09:00 slot on March 9, leaving 9 slots × 2 = 18 places.
        AmenityClosure closure = new AmenityClosure();
        closure.amenityId = gym.id; closure.reason = "Floor repair";
        closure.startAt = LocalDateTime.of(2026, 3, 9, 9, 0).atZone(zone).toInstant(); closure.endAt = closure.startAt.plus(Duration.ofHours(1));
        closures.saveAndFlush(closure);

        Instant march2 = LocalDateTime.of(2026, 3, 2, 9, 0).atZone(zone).toInstant();
        reservation(gym, maya, march2, null);
        reservation(gym, omar, march2, null);
        // Starts in March, cancelled in April: a March cancellation.
        reservation(gym, maya, march2.plus(Duration.ofDays(7)), LocalDate.of(2026, 4, 1).atStartOfDay(zone).toInstant());
        // Starts in February, cancelled in March: not in March's figures.
        reservation(gym, omar, march2.minus(Duration.ofDays(7)), march2);
        // Edges of the Pacific day: the last hour of March 31 counts, midnight on April 1 does not.
        reservation(annex, maya, LocalDateTime.of(2026, 3, 31, 23, 0).atZone(zone).toInstant(), null);
        reservation(annex, omar, LocalDate.of(2026, 4, 1).atStartOfDay(zone).toInstant(), null);
        reservation(annex, omar, LocalDateTime.of(2026, 3, 3, 9, 0).atZone(zone).toInstant(), null);
        reservation(amenity(otherManager.community, "Pool", 1), otherResident, march2, null);

        JsonNode report = call(manager, get("/api/manager/statistics?from=2026-03-01&to=2026-03-31"), null, 200);
        Map<String, JsonNode> metrics = metrics(report);
        assertThat(metrics.get("amenities.reservations").asLong()).isEqualTo(5);
        assertThat(metrics.get("amenities.active").asLong()).isEqualTo(4);
        assertThat(metrics.get("amenities.cancelled").asLong()).isEqualTo(1);
        assertThat(metrics.get("amenities.utilization").decimalValue()).isEqualByComparingTo("22.2");
        JsonNode byAmenity = report.get("sections").get(3).get("breakdown");
        assertThat(report.get("sections").get(3).get("key").asText()).isEqualTo("amenities");
        assertThat(byAmenity).hasSize(2);
        assertThat(byAmenity.get(0).get("label").asText()).isEqualTo("Gym");
        assertThat(byAmenity.get(0).get("value").asLong()).isEqualTo(2);
        assertThat(byAmenity.get(1).get("label").asText()).isEqualTo("Gym");
        assertThat(byAmenity.get(1).get("value").asLong()).isEqualTo(2);

        // A period with no activity: counts are 0 and the completion rate has no denominator.
        Map<String, JsonNode> empty = metrics(call(manager, get("/api/manager/statistics?from=2025-01-01&to=2025-01-31"), null, 200));
        assertThat(empty.get("maintenance.submitted").asLong()).isZero();
        assertThat(empty.get("maintenance.completionRate").isNull()).isTrue();
        assertThat(empty.get("amenities.reservations").asLong()).isZero();
        assertThat(empty.get("amenities.utilization").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("payments.overdueAmount").decimalValue()).isEqualByComparingTo("0");
        // With no opening hours there are no places, so utilization has no denominator either.
        weeklyHours.deleteAll(weeklyHours.findByAmenityIdOrderByOpenTimeAsc(gym.id));
        assertThat(metrics(call(manager, get("/api/manager/statistics?from=2026-03-01&to=2026-03-31"), null, 200)).get("amenities.utilization").isNull()).isTrue();
    }
}
