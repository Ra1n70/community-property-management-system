package com.cpms.community.maintenance;

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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:maintenanceflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","locker.pickup-code-secret=test-only","demo.manager-password=","demo.invite-code=test"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class MaintenanceFlowTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path photoDir;
    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry){registry.add("maintenance.upload-dir",()->photoDir.toString());}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    Account manager,resident,other,pending,provider;
    @BeforeEach void setup(){String community=UUID.randomUUID().toString();manager=account(community,Account.Role.MANAGER,Account.Status.APPROVED);resident=account(community,Account.Role.RESIDENT,Account.Status.APPROVED);other=account(UUID.randomUUID().toString(),Account.Role.RESIDENT,Account.Status.APPROVED);pending=account(community,Account.Role.RESIDENT,Account.Status.PENDING);provider=account(community,Account.Role.PROVIDER,Account.Status.APPROVED);}
    Account account(String community,Account.Role role,Account.Status status){Account a=new Account();a.email=UUID.randomUUID()+"@test.local";a.name="Test user";a.passwordHash="test";a.community=community;a.room="101";a.role=role;a.status=status;if(role==Account.Role.PROVIDER)a.providerType=Account.ProviderType.MAINTENANCE;return accounts.saveAndFlush(a);}
    JsonNode call(Account a,MockHttpServletRequestBuilder req,Object body,int expected)throws Exception{
        if(a!=null)req.with(user(a.email).roles(a.role.name()));
        req.with(csrf());if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
        String response=mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.nullNode():json.readTree(response);
    }

    @Test void validationMessagesRemainEnglishForChineseRequests()throws Exception{
        var body=new HashMap<String,Object>(Map.of("category","Plumbing","location","Kitchen","description","Leaking tap","preferredTime","2020-01-01T00:00:00Z"));
        var error=call(resident,post("/api/maintenance").locale(Locale.SIMPLIFIED_CHINESE),body,400);
        assertThat(error.get("message").asText()).isEqualTo("preferredTime: must be a future date");
        body.remove("preferredTime");body.put("description"," ");
        assertThat(call(resident,post("/api/maintenance").locale(Locale.SIMPLIFIED_CHINESE),body,400).get("message").asText()).isEqualTo("description: must not be blank");
        body.put("description","x".repeat(3001));
        assertThat(call(resident,post("/api/maintenance").locale(Locale.SIMPLIFIED_CHINESE),body,400).get("message").asText()).contains("size must be between 0 and 3000");
    }
    JsonNode create()throws Exception{return call(resident,post("/api/maintenance"),Map.of("category","Plumbing","location","Kitchen","description","Leaking tap"),201);}
    String path(JsonNode t,String action){return "/api/maintenance/"+t.get("id").asLong()+"/"+action;}
    Map<String,Object> assign(JsonNode t,Account worker){return Map.of("version",t.get("version").asLong(),"assigneeId",worker.id,"priority","NORMAL","note","Please inspect");}
    JsonNode act(Account a,JsonNode t,String action,int expected)throws Exception{return call(a,post(path(t,action)),Map.of("version",t.get("version").asLong(),"note","Repair checked and completed"),expected);}
    @Test void fullLifecycleAndHistory()throws Exception{
        JsonNode t=create();assertThat(t.get("residentId").asLong()).isEqualTo(resident.id);
        t=call(manager,post(path(t,"assign")),assign(t,provider),200);
        act(manager,t,"start",403);
        t=act(provider,t,"start",200);assertThat(t.get("status").asText()).isEqualTo("IN_PROGRESS");
        act(resident,t,"confirm",409);
        t=act(provider,t,"resolve",200);act(provider,t,"confirm",403);
        t=act(resident,t,"confirm",200);assertThat(t.get("status").asText()).isEqualTo("COMPLETED");
        act(resident,t,"confirm",409);
        assertThat(call(resident,get(path(t,"history")),null,200).size()).isEqualTo(5);
    }
    @Test void ownershipCommunityApprovalAndCsrf()throws Exception{
        JsonNode t=create();Account neighbor=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);
        call(null,get("/api/maintenance"),null,401);call(pending,get("/api/maintenance"),null,403);
        call(provider,post("/api/maintenance"),Map.of("category","Other","location","Lobby","description","Issue"),403);
        for(Account a:List.of(neighbor,other,provider)){assertThat(call(a,get("/api/maintenance"),null,200).size()).isZero();call(a,get(path(t,"history")),null,404);}
        call(resident,get("/api/maintenance/assignees"),null,403);
        Account foreign=account(other.community,Account.Role.MANAGER,Account.Status.APPROVED);
        call(foreign,post(path(t,"assign")),assign(t,provider),404);
        call(manager,post(path(t,"assign")),assign(t,foreign),400);
        mvc.perform(post("/api/maintenance").with(user(resident.email).roles("RESIDENT")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    JsonNode step(Account a,JsonNode t,String action,Map<String,Object> extra,int expected)throws Exception{
        Map<String,Object> body=new java.util.HashMap<>(extra);body.put("version",t.get("version").asLong());return call(a,post(path(t,action)),body,expected);}
    @Test void unableToFixGoesBackToManagerWhoCanReassignOrCloseAsUnresolved()throws Exception{
        JsonNode c=create();JsonNode t=call(manager,post(path(c,"assign")),assign(c,provider),200);
        t=act(provider,t,"start",200);
        step(provider,t,"resolve",Map.of("outcome","UNABLE_TO_FIX","note"," "),400);
        t=step(provider,t,"resolve",Map.of("outcome","UNABLE_TO_FIX","note","Part is discontinued."),200);
        assertThat(t.get("status").asText()).isEqualTo("UNABLE_TO_FIX");assertThat(t.get("result").asText()).isEqualTo("Part is discontinued.");
        step(resident,t,"confirm",Map.of(),409);step(provider,t,"close",Map.of("note","Closing"),403);
        JsonNode reassigned=call(manager,post(path(t,"assign")),assign(t,manager),200);
        assertThat(reassigned.get("status").asText()).isEqualTo("ACCEPTED");
        reassigned=act(manager,reassigned,"start",200);
        reassigned=step(manager,reassigned,"resolve",Map.of("outcome","UNABLE_TO_FIX","note","Building-wide issue."),200);
        step(manager,reassigned,"close",Map.of("note"," "),400);
        JsonNode closed=step(manager,reassigned,"close",Map.of("note","Needs city utility repair."),200);
        assertThat(closed.get("status").asText()).isEqualTo("UNRESOLVED");assertThat(closed.get("completedAt").isNull()).isFalse();
        call(manager,post(path(closed,"assign")),assign(closed,provider),409);
        assertThat(call(manager,get(path(closed,"history")),null,200).toString()).contains("UNABLE_TO_FIX","CLOSE");
    }
    @Test void residentCanReportStillNotFixedWhichReturnsToInProgress()throws Exception{
        JsonNode c=create();JsonNode t=call(manager,post(path(c,"assign")),assign(c,provider),200);
        t=act(provider,t,"start",200);t=step(provider,t,"resolve",Map.of("outcome","FIXED","note","Replaced washer."),200);
        assertThat(t.get("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        step(resident,t,"reopen",Map.of("note",""),400);step(provider,t,"reopen",Map.of("note","Still leaking"),403);
        t=step(resident,t,"reopen",Map.of("note","Still leaking under the sink."),200);
        assertThat(t.get("status").asText()).isEqualTo("IN_PROGRESS");
        t=step(provider,t,"resolve",Map.of("note","Replaced the trap as well."),200);
        assertThat(t.get("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(act(resident,t,"confirm",200).get("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void assignmentNoteIsOptional()throws Exception{
        JsonNode t=create();
        JsonNode assigned=call(manager,post(path(t,"assign")),Map.of("version",t.get("version").asLong(),"assigneeId",provider.id,"priority","NORMAL"),200);
        assertThat(assigned.get("status").asText()).isEqualTo("ACCEPTED");
        assigned=call(manager,post(path(assigned,"assign")),Map.of("version",assigned.get("version").asLong(),"assigneeId",manager.id,"priority","URGENT","note","  "),200);
        assertThat(assigned.get("assigneeId").asLong()).isEqualTo(manager.id);
    }
    @Test void reassignRevokesOldWorkerAndRejectRequiresReason()throws Exception{
        JsonNode t=create();call(manager,post(path(t,"reject")),Map.of("version",t.get("version").asLong(),"note"," "),400);
        JsonNode assigned=call(manager,post(path(t,"assign")),assign(t,provider),200);
        call(manager,post(path(t,"assign")),assign(t,manager),409);
        assigned=act(provider,assigned,"start",200);
        assigned=call(manager,post(path(assigned,"assign")),assign(assigned,manager),200);
        act(provider,assigned,"resolve",404);call(provider,get(path(assigned,"history")),null,404);
        assertThat(assigned.get("status").asText()).isEqualTo("ACCEPTED");
        act(manager,assigned,"start",200);
        JsonNode rejected=act(manager,create(),"reject",200);assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        call(manager,post(path(rejected,"assign")),assign(rejected,provider),409);
    }
    @Test void invalidPhotoLinksAndSizeAreRejected()throws Exception{
        for(Object links:List.of(List.of("relative/path"),List.of("javascript:alert(1)"),List.of("https://a.test","https://b.test","https://c.test","https://d.test"))){
            call(resident,post("/api/maintenance"),Map.of("category","Other","location","Lobby","description","Issue","imageUrls",links),400);
        }
    }

    @Test void photoUploadIsPrivateAndInvalidUploadsRollBack()throws Exception{
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        var request=new org.springframework.mock.web.MockMultipartFile("request","","application/json",json.writeValueAsBytes(Map.of("category","Other","location","Kitchen","description","Photo issue")));
        var photo=new org.springframework.mock.web.MockMultipartFile("photos","photo.png","image/png",output.toByteArray());
        String response=mvc.perform(multipart("/api/maintenance").file(request).file(photo).with(user(resident.email).roles("RESIDENT")).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode t=json.readTree(response);String url=t.get("imageUrls").get(0).asText();
        mvc.perform(get(url).with(user(resident.email).roles("RESIDENT"))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get(url).with(user(manager.email).roles("MANAGER"))).andExpect(status().isOk());
        mvc.perform(get(url).with(user(other.email).roles("RESIDENT"))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(user(provider.email).roles("PROVIDER"))).andExpect(status().isNotFound());
        call(manager,post(path(t,"assign")),assign(t,provider),200);
        mvc.perform(get(url).with(user(provider.email).roles("PROVIDER"))).andExpect(status().isOk());
        int before=call(resident,get("/api/maintenance"),null,200).size();
        var invalid=new org.springframework.mock.web.MockMultipartFile("photos","fake.png","image/png","not an image".getBytes());
        mvc.perform(multipart("/api/maintenance").file(request).file(invalid).with(user(resident.email).roles("RESIDENT")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/maintenance").file(request).file(photo).file(photo).file(photo).file(photo).with(user(resident.email).roles("RESIDENT")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(call(resident,get("/api/maintenance"),null,200).size()).isEqualTo(before);
    }
}
