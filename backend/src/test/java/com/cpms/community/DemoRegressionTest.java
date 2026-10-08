package com.cpms.community;

import com.fasterxml.jackson.databind.*;
import com.cpms.community.maintenance.*;
import com.cpms.community.payment.*;
import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.repository.ReservationRepository;
import com.cpms.community.discussion.repository.DiscussionRepository;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.repository.LockerRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:demoregression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","locker.pickup-code-secret=test-only","demo.manager-password=","demo.invite-code=test"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class DemoRegressionTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired AccountRepository accounts;
    @Autowired MaintenanceRepository tickets; @Autowired BillRepository bills; @Autowired ReservationRepository reservations;
    @Autowired RoomChangeRepository changes; @Autowired DiscussionRepository discussions; @Autowired LockerRepository lockers;
    @Autowired com.cpms.community.locker.repository.LockerCellRepository cells;
    @Autowired com.cpms.community.locker.repository.ParcelRepository parcels;
    Account manager,resident,repair,delivery,foreign;
    @BeforeEach void setup() {
        String community=UUID.randomUUID().toString();
        manager=account(community,Account.Role.MANAGER,null);resident=account(community,Account.Role.RESIDENT,null);
        repair=account(community,Account.Role.PROVIDER,Account.ProviderType.MAINTENANCE);
        delivery=account(community,Account.Role.PROVIDER,Account.ProviderType.DELIVERY);
        foreign=account(UUID.randomUUID().toString(),Account.Role.MANAGER,null);
    }
    Account account(String community,Account.Role role,Account.ProviderType type) {
        Account a=new Account();a.email=UUID.randomUUID()+"@test.local";a.passwordHash="test";a.name=role.name()+" user";a.room="OLD-601";
        a.community=community;a.role=role;a.status=Account.Status.APPROVED;a.providerType=type;return accounts.saveAndFlush(a);
    }
    JsonNode call(Account a,MockHttpServletRequestBuilder req,Object body,int expected)throws Exception {
        req.with(user(a.email).roles(a.role.name())).with(csrf());
        if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
        String response=mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.nullNode():json.readTree(response);
    }
    JsonNode ticket(Account owner)throws Exception {return call(owner,post("/api/maintenance"),Map.of("category","Plumbing","location","Kitchen","description","Leaking tap"),201);}
    @Test void roomUpdateSynchronizesAllOwnedLabelsWithoutChangingBusinessHistory()throws Exception {
        JsonNode t=ticket(resident);Account neighbor=account(manager.community,Account.Role.RESIDENT,null);JsonNode other=ticket(neighbor);
        Bill b=new Bill();b.community=resident.community;b.residentId=resident.id;b.residentName=resident.name;b.room=resident.room;
        b.createdBy=manager.id;b.title="Paid service fee";b.amount=new BigDecimal("25.00");b.dueDate=LocalDate.now().plusDays(2);b.status="PAID";b.receiptNumber="DEMO-original";b.paidAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);b=bills.saveAndFlush(b);
        Reservation r=new Reservation();r.amenityId=1L;r.accountId=resident.id;r.community=resident.community;r.room=resident.room;r.guestName=resident.name;
        r.startAt=Instant.now().plusSeconds(3600);r.endAt=r.startAt.plusSeconds(3600);r.slotKey="unique-"+UUID.randomUUID();r=reservations.saveAndFlush(r);
        call(manager,put("/api/manager/residents/"+resident.id+"/room"),Map.of("room","601","reason","Verified correction"),200);
        var updated=tickets.findById(t.get("id").asLong()).orElseThrow();assertThat(updated.room).isEqualTo("601");assertThat(updated.location).isEqualTo("Kitchen");
        assertThat(updated.version).isGreaterThan(t.get("version").asLong());
        assertThat(tickets.findById(other.get("id").asLong()).orElseThrow().room).isEqualTo("OLD-601");
        var paid=bills.findById(b.id).orElseThrow();assertThat(paid.room).isEqualTo("601");assertThat(paid.receiptNumber).isEqualTo(b.receiptNumber);assertThat(paid.paidAt).isEqualTo(b.paidAt);assertThat(paid.status).isEqualTo("PAID");
        var booking=reservations.findById(r.id).orElseThrow();assertThat(booking.room).isEqualTo("601");assertThat(booking.slotKey).isEqualTo(r.slotKey);
        var audit=changes.findByAccountIdOrderByCreatedAtDesc(resident.id).getFirst();assertThat(audit.oldRoom).isEqualTo("OLD-601");assertThat(audit.newRoom).isEqualTo("601");
        call(manager,put("/api/manager/residents/"+resident.id+"/room"),Map.of("room","601","reason","Repeated"),400);
        assertThat(changes.findByAccountIdOrderByCreatedAtDesc(resident.id)).hasSize(1);
        call(foreign,put("/api/manager/residents/"+resident.id+"/room"),Map.of("room","602","reason","Invalid"),404);
        assertThat(bills.findById(b.id).orElseThrow().room).isEqualTo("601");
    }
    @Test void finishedMaintenanceTicketsKeepTheRoomWhereWorkWasRequested()throws Exception {
        long open=ticket(resident).get("id").asLong();
        Map<MaintenanceTicket.Status,Long> finished=new java.util.EnumMap<>(MaintenanceTicket.Status.class);
        for(MaintenanceTicket.Status s:List.of(MaintenanceTicket.Status.COMPLETED,MaintenanceTicket.Status.REJECTED,MaintenanceTicket.Status.UNRESOLVED)) {
            var t=tickets.findById(ticket(resident).get("id").asLong()).orElseThrow();t.status=s;finished.put(s,tickets.saveAndFlush(t).id);
        }
        var waiting=tickets.findById(ticket(resident).get("id").asLong()).orElseThrow();waiting.status=MaintenanceTicket.Status.PENDING_CONFIRMATION;tickets.saveAndFlush(waiting);
        call(manager,put("/api/manager/residents/"+resident.id+"/room"),Map.of("room","702"),200);
        assertThat(tickets.findById(open).orElseThrow().room).isEqualTo("702");
        assertThat(tickets.findById(waiting.id).orElseThrow().room).isEqualTo("702");
        finished.forEach((status,id)->assertThat(tickets.findById(id).orElseThrow().room).as(status.name()).isEqualTo("OLD-601"));
    }
    @Test void persistedMessageHistoryRetainsSenderNamesRolesAndIdsInBothPortals()throws Exception {
        var sent=call(resident,post("/api/direct-messages/me/messages"),Map.of("content","Hello","clientRequestId","resident-1"),200);
        long conversation=sent.get("conversationId").asLong();
        call(manager,post("/api/direct-messages/conversations/"+conversation+"/messages"),Map.of("content","Hello Alex","clientRequestId","manager-1"),200);
        for(Account actor:List.of(manager,resident)) {
            String path=actor==manager?"/api/direct-messages/conversations/"+conversation+"/messages":"/api/direct-messages/me/messages";
            var history=call(actor,get(path),null,200).get("messages");
            assertThat(history.get(0).get("senderName").asText()).isEqualTo(resident.name);assertThat(history.get(0).get("senderId").asLong()).isEqualTo(resident.id);
            assertThat(history.get(1).get("senderName").asText()).isEqualTo(manager.name);assertThat(history.get(1).get("senderRole").asText()).isEqualTo("MANAGER");
            assertThat(history.get(0).get("conversationId").asLong()).isEqualTo(conversation);
        }
    }
    @Test void providerTypeEnforcesAssignmentsAndCommunityDeliveryAccess()throws Exception {
        var t=ticket(resident);long id=t.get("id").asLong();
        call(manager,post("/api/maintenance/"+id+"/assign"),Map.of("version",t.get("version").asLong(),"assigneeId",delivery.id,"priority","NORMAL","note","Invalid delivery assignment"),400);
        var options=call(manager,get("/api/maintenance/assignees"),null,200);assertThat(options.toString()).contains(repair.id.toString()).doesNotContain("\"id\":"+delivery.id+",");
        call(manager,post("/api/maintenance/"+id+"/assign"),Map.of("version",t.get("version").asLong(),"assigneeId",repair.id,"priority","NORMAL","note","Inspect tap"),200);
        call(manager,put("/api/manager/providers/"+repair.id+"/service-type"),Map.of("providerType","DELIVERY"),409);
        call(delivery,get("/api/maintenance"),null,403);call(delivery,get("/api/maintenance/"+id+"/history"),null,403);
        call(repair,get("/api/provider/deliveries/couriers"),null,403);
        call(delivery,get("/api/provider/deliveries/couriers"),null,200);
        call(foreign,put("/api/manager/providers/"+delivery.id+"/service-type"),Map.of("providerType","MAINTENANCE"),404);
        Account unclassified=account(manager.community,Account.Role.PROVIDER,null);call(unclassified,get("/api/maintenance"),null,403);call(unclassified,get("/api/provider/deliveries/couriers"),null,403);
        call(manager,put("/api/manager/providers/"+unclassified.id+"/service-type"),Map.of("providerType","MAINTENANCE"),200);
        call(unclassified,get("/api/maintenance"),null,200);
    }
    Locker locker(String community) {Locker l=new Locker();l.community=community;l.lockerNumber=UUID.randomUUID().toString();l.location="Lobby";return lockers.saveAndFlush(l);}
    @Test void searchIncludesCommunityContentButExcludesDeletedPostsAndProviders()throws Exception {
        var notice=Map.of("title","Lounge hours","content","Lounge hours and opening times","publishedAt",Instant.now().toString());
        var own=call(manager,post("/api/announcements"),notice,201);call(foreign,post("/api/announcements"),notice,201);
        var post=call(resident,post("/api/discussions"),Map.of("title","Lounge ideas","content","Lounge games","category","LIFE"),201);
        var hidden=discussions.findById(post.get("id").asLong()).orElseThrow();hidden.deleted=true;discussions.saveAndFlush(hidden);
        call(resident,post("/api/discussions"),Map.of("title","Lounge plans","content","Lounge planning","category","LIFE"),201);
        var results=call(resident,get("/api/search?q=Lounge&type=ANNOUNCEMENT,DISCUSSION"),null,200);
        assertThat(results.get("total").asInt()).isEqualTo(2);assertThat(results.toString()).doesNotContain("Lounge ideas");
        call(foreign,get("/api/search/ANNOUNCEMENT/"+own.get("id").asLong()),null,404);
        call(resident,get("/api/search/DISCUSSION/"+post.get("id").asLong()),null,404);
        for(Account p:List.of(delivery,repair))assertThat(call(p,get("/api/search?type=ANNOUNCEMENT,DISCUSSION"),null,200).get("total").asInt()).isZero();
    }
}
