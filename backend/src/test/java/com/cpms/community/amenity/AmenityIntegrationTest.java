package com.cpms.community.amenity;

import com.cpms.community.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:amenityintegration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","locker.pickup-code-secret=test-only","demo.manager-password=","demo.invite-code=test","amenity.upload-dir=target/test-uploads/amenities"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class AmenityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    Account manager,resident,other,pending,provider;
    LocalDate date=LocalDate.now(AmenityConfig.ZONE).plusDays(2);
    @BeforeEach void setup(){String community=UUID.randomUUID().toString();manager=account(community,Account.Role.MANAGER,Account.Status.APPROVED);resident=account(community,Account.Role.RESIDENT,Account.Status.APPROVED);other=account(UUID.randomUUID().toString(),Account.Role.RESIDENT,Account.Status.APPROVED);pending=account(community,Account.Role.RESIDENT,Account.Status.PENDING);provider=account(community,Account.Role.PROVIDER,Account.Status.APPROVED);}
    Account account(String community,Account.Role role,Account.Status status){Account a=new Account();a.email=UUID.randomUUID()+"@test.local";a.name="Test user";a.passwordHash="test";a.community=community;a.room="101";a.role=role;a.status=status;return accounts.saveAndFlush(a);}
    JsonNode call(Account a,MockHttpServletRequestBuilder req,Object body,int expected)throws Exception{
        if(a!=null)req.with(user(a.email).roles(a.role.name()));
        req.with(csrf());if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
        String response=mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.nullNode():json.readTree(response);
    }
    long facility()throws Exception{
        Map<String,Object> body=new HashMap<>(Map.of("name","Gym","type","Fitness","location","Lobby","capacity",8,"slotDurationMinutes",60,"maxSlotsPerDay",1,"maxAdvanceDays",7));body.put("community",other.community);
        JsonNode a=call(manager,post("/api/manager/amenities"),body,201);assertThat(a.get("community").asText()).isEqualTo(manager.community);
        long id=a.get("id").asLong();
        call(manager,put("/api/manager/amenities/"+id+"/hours"),Map.of("hours",List.of(Map.of("dayOfWeek",date.getDayOfWeek().name(),"openTime","09:00","closeTime","13:00"))),200);
        return id;
    }
    JsonNode slots(long id)throws Exception{return call(resident,get("/api/resident/amenities/"+id+"/slots?date="+date),null,200);}
    @Test void bookingOwnershipCancellationAndDailyLimit()throws Exception{
        long id=facility();JsonNode times=slots(id);assertThat(times.size()).isEqualTo(4);
        JsonNode booking=call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",times.get(0).get("startAt").asText(),"room","999","guestName","Spoofed","community",other.community),201);
        assertThat(booking.get("room").asText()).isEqualTo("101");assertThat(booking.get("guestName").asText()).isEqualTo(resident.name);
        call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",times.get(0).get("startAt").asText()),409);
        call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",times.get(1).get("startAt").asText()),400);
        Account housemate=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);
        assertThat(call(housemate,get("/api/resident/reservations"),null,200).size()).isZero();
        String cancel="/api/resident/reservations/"+booking.get("id").asLong()+"/cancel";
        call(housemate,post(cancel),null,404);call(other,post(cancel),null,404);
        resident.room="202";accounts.saveAndFlush(resident);
        assertThat(call(resident,get("/api/resident/reservations"),null,200).size()).isEqualTo(1);
        assertThat(call(resident,post(cancel),null,200).get("status").asText()).isEqualTo("CANCELLED");
        call(resident,post(cancel),null,409);
        assertThat(slots(id).get(0).get("status").asText()).isEqualTo("AVAILABLE");
    }
    @Test void roleCommunityAndCsrfRestrictions()throws Exception{
        long id=facility();
        call(null,get("/api/resident/amenities"),null,401);call(pending,get("/api/resident/amenities"),null,403);call(provider,get("/api/resident/amenities"),null,403);
        call(resident,get("/api/manager/amenities"),null,403);call(other,get("/api/resident/amenities/"+id),null,404);
        Account otherManager=account(other.community,Account.Role.MANAGER,Account.Status.APPROVED);
        call(otherManager,put("/api/manager/amenities/"+id+"/hours"),Map.of("hours",List.of()),404);
        mvc.perform(post("/api/resident/reservations").with(user(resident.email).roles("RESIDENT")).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void closuresPreserveBookingsAndManagerCanCancel()throws Exception{
        long id=facility();String start=slots(id).get(0).get("startAt").asText();
        JsonNode booking=call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",start),201);
        JsonNode affected=call(manager,post("/api/manager/amenities/"+id+"/closures"),Map.of("startAt",date+"T09:00","endAt",date+"T11:00","reason","Maintenance"),201);
        assertThat(affected.size()).isEqualTo(1);assertThat(slots(id).get(1).get("status").asText()).isEqualTo("CLOSED");
        String cancel="/api/manager/reservations/"+booking.get("id").asLong()+"/cancel";
        assertThat(call(manager,post(cancel),Map.of("reason","Maintenance"),200).get("cancelReason").asText()).isEqualTo("Maintenance");
    }
    @Test void closureAndManagerCancelReasonsAreOptional()throws Exception{
        long id=facility();String start=slots(id).get(0).get("startAt").asText();
        JsonNode booking=call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",start),201);
        assertThat(call(manager,post("/api/manager/amenities/"+id+"/closures"),Map.of("startAt",date+"T09:00","endAt",date+"T11:00"),201).size()).isEqualTo(1);
        assertThat(slots(id).get(1).get("status").asText()).isEqualTo("CLOSED");
        String cancel="/api/manager/reservations/"+booking.get("id").asLong()+"/cancel";
        JsonNode cancelled=call(manager,post(cancel),Map.of("reason","  "),200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");assertThat(cancelled.get("cancelReason").isNull()).isTrue();
        call(manager,post(cancel),Map.of(),409);
    }
    @Test void managerMutationsUseSessionCommunityDespiteSpoofedBody()throws Exception{
        long id=facility();
        Account outsider=account(other.community,Account.Role.MANAGER,Account.Status.APPROVED);
        Map<String,Object> update=new HashMap<>(Map.of("name","Updated gym","type","Fitness","location","Lobby","capacity",8,"slotDurationMinutes",60,"maxSlotsPerDay",1,"maxAdvanceDays",7));
        update.put("community",manager.community);
        call(outsider,put("/api/manager/amenities/"+id),update,404);
        update.put("community",other.community);
        assertThat(call(manager,put("/api/manager/amenities/"+id),update,200).get("community").asText()).isEqualTo(manager.community);
        Map<String,Object> hours=Map.of("community",manager.community,"hours",List.of(Map.of("dayOfWeek",date.getDayOfWeek().name(),"openTime","09:00","closeTime","13:00")));
        call(outsider,put("/api/manager/amenities/"+id+"/hours"),hours,404);
        call(manager,put("/api/manager/amenities/"+id+"/hours"),Map.of("community",other.community,"hours",hours.get("hours")),200);
        JsonNode booking=call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",slots(id).get(0).get("startAt").asText()),201);
        Map<String,Object> closure=Map.of("community",manager.community,"startAt",date+"T09:00","endAt",date+"T11:00","reason","Maintenance");
        call(outsider,post("/api/manager/amenities/"+id+"/closures"),closure,404);
        assertThat(call(manager,post("/api/manager/amenities/"+id+"/closures"),Map.of("community",other.community,"startAt",date+"T09:00","endAt",date+"T11:00","reason","Maintenance"),201).size()).isEqualTo(1);
        String cancel="/api/manager/reservations/"+booking.get("id").asLong()+"/cancel";
        call(outsider,post(cancel),Map.of("community",manager.community,"reason","Spoofed"),404);
        assertThat(call(manager,post(cancel),Map.of("community",other.community,"room","999","reason","Maintenance"),200).get("status").asText()).isEqualTo("CANCELLED");
    }
    @Test void feesKeepExactCents()throws Exception{
        long id=facility();
        Map<String,Object> paid=new HashMap<>(Map.of("name","Party room","type","Room","location","Clubhouse","capacity",1,"slotDurationMinutes",60,"maxSlotsPerDay",1,"maxAdvanceDays",7,"chargeable",true));
        paid.put("fee",new java.math.BigDecimal("12.345"));
        call(manager,put("/api/manager/amenities/"+id),paid,400);
        paid.put("fee",new java.math.BigDecimal("0.10"));
        assertThat(call(manager,put("/api/manager/amenities/"+id),paid,200).get("fee").decimalValue()).isEqualByComparingTo("0.10");
        paid.put("fee",new java.math.BigDecimal("12.34"));
        assertThat(call(manager,put("/api/manager/amenities/"+id),paid,200).get("fee").asText()).isEqualTo("12.34");
        JsonNode booking=call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",slots(id).get(0).get("startAt").asText()),201);
        assertThat(booking.get("fee").asText()).isEqualTo("12.34");
        paid.put("chargeable",false);
        assertThat(call(manager,put("/api/manager/amenities/"+id),paid,200).get("fee").decimalValue()).isEqualByComparingTo("0");
        assertThat(call(resident,get("/api/resident/reservations"),null,200).get(0).get("fee").asText()).isEqualTo("12.34");
    }
    @Test void capacityAllowsThatManyHouseholdsPerSlot()throws Exception{
        long id=facility();
        Map<String,Object> two=new HashMap<>(Map.of("name","Gym","type","Fitness","location","Lobby","capacity",2,"slotDurationMinutes",60,"maxSlotsPerDay",2,"maxAdvanceDays",7));
        call(manager,put("/api/manager/amenities/"+id),two,200);
        String start=slots(id).get(0).get("startAt").asText();Map<String,Object> book=Map.of("amenityId",id,"startAt",start);
        assertThat(slots(id).get(0).get("remaining").asInt()).isEqualTo(2);
        JsonNode first=call(resident,post("/api/resident/reservations"),book,201);
        assertThat(slots(id).get(0).get("remaining").asInt()).isEqualTo(1);
        Account housemate=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);
        call(housemate,post("/api/resident/reservations"),book,409);
        call(resident,post("/api/resident/reservations"),book,409);
        Account neighbor=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);neighbor.room="102";accounts.saveAndFlush(neighbor);
        call(neighbor,post("/api/resident/reservations"),book,201);
        JsonNode full=slots(id).get(0);
        assertThat(full.get("status").asText()).isEqualTo("BOOKED");assertThat(full.get("remaining").asInt()).isZero();
        Account third=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);third.room="103";accounts.saveAndFlush(third);
        call(third,post("/api/resident/reservations"),book,409);
        call(resident,post("/api/resident/reservations/"+first.get("id").asLong()+"/cancel"),null,200);
        assertThat(slots(id).get(0).get("remaining").asInt()).isEqualTo(1);
        call(third,post("/api/resident/reservations"),book,201);
    }
    @Test void managerCanListAndRemoveClosures()throws Exception{
        long id=facility();String closures="/api/manager/amenities/"+id+"/closures";
        call(manager,post(closures),Map.of("startAt",date+"T09:00","endAt",date+"T11:00","reason","Floor repair"),201);
        JsonNode listed=call(manager,get(closures),null,200);
        assertThat(listed.size()).isEqualTo(1);assertThat(listed.get(0).get("reason").asText()).isEqualTo("Floor repair");
        assertThat(slots(id).get(0).get("status").asText()).isEqualTo("CLOSED");
        String remove=closures+"/"+listed.get(0).get("id").asLong();
        call(resident,delete(remove),null,403);
        call(account(other.community,Account.Role.MANAGER,Account.Status.APPROVED),delete(remove),null,404);
        call(manager,delete(remove),null,204);
        assertThat(call(manager,get(closures),null,200).size()).isZero();
        assertThat(slots(id).get(0).get("status").asText()).isEqualTo("AVAILABLE");
        call(manager,delete(remove),null,404);
    }
    @Test void hoursCannotDropUpcomingBookings()throws Exception{
        long id=facility();String hours="/api/manager/amenities/"+id+"/hours",day=date.getDayOfWeek().name();
        call(resident,post("/api/resident/reservations"),Map.of("amenityId",id,"startAt",slots(id).get(0).get("startAt").asText()),201);
        JsonNode refused=call(manager,put(hours),Map.of("hours",List.of(Map.of("dayOfWeek",day,"openTime","11:00","closeTime","13:00"))),409);
        assertThat(refused.get("message").asText()).contains("1 upcoming reservation");
        assertThat(call(manager,get(hours),null,200).get(0).get("openTime").asText()).startsWith("09:00");
        call(manager,put(hours),Map.of("hours",List.of(Map.of("dayOfWeek",day,"openTime","09:00","closeTime","11:00"))),200);
    }
    @Test void replacingAndRemovingAnImageKeepsOneFile()throws Exception{
        long id=facility();String url="/api/manager/amenities/"+id+"/image";
        byte[] first={(byte)0x89,'P','N','G',13,10,26,10,1},second={(byte)0x89,'P','N','G',13,10,26,10,2};
        JsonNode uploaded=upload(url,first);
        String firstUrl=uploaded.get("imageUrl").asText();assertThat(firstUrl).contains("?v=");
        assertThat(upload(url,second).get("imageUrl").asText()).isNotEqualTo(firstUrl);
        assertThat(mvc.perform(get("/api/resident/amenities/"+id+"/image").with(user(resident.email).roles("RESIDENT"))).andReturn().getResponse().getContentAsByteArray()).isEqualTo(second);
        java.nio.file.Path dir=java.nio.file.Path.of("target/test-uploads/amenities",String.valueOf(id));
        try(var files=java.nio.file.Files.list(dir)){assertThat(files.count()).isEqualTo(1);}
        call(manager,delete(url),null,204);
        call(resident,get("/api/resident/amenities/"+id+"/image"),null,404);
        try(var files=java.nio.file.Files.list(dir)){assertThat(files.count()).isZero();}
    }
    JsonNode upload(String url,byte[] bytes)throws Exception{
        String response=mvc.perform(multipart(url).file(new org.springframework.mock.web.MockMultipartFile("file","photo.png","image/png",bytes))
                .with(user(manager.email).roles("MANAGER")).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
    @Test void overlappingHoursAreRejectedAndLateHoursTerminate()throws Exception{
        long id=facility();Map<String,String> h=Map.of("dayOfWeek",date.getDayOfWeek().name(),"openTime","09:00","closeTime","11:00");
        call(manager,put("/api/manager/amenities/"+id+"/hours"),Map.of("hours",List.of(h,h)),400);
        call(manager,put("/api/manager/amenities/"+id+"/hours"),Map.of("hours",List.of(Map.of("dayOfWeek",date.getDayOfWeek().name(),"openTime","22:00","closeTime","23:59"))),200);
        assertThat(slots(id).size()).isEqualTo(1);
    }
}
