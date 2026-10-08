package com.cpms.community.announcement;
import com.fasterxml.jackson.databind.*;
import com.cpms.community.*;
import com.cpms.community.announcement.repository.AnnouncementRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:AnnouncementIntegration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","locker.pickup-code-secret=test-only","demo.manager-password=","demo.invite-code=test"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class AnnouncementIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired AccountRepository accounts; @Autowired AnnouncementRepository repository;
 String manager,resident,other,otherResident,provider,pending;
 @BeforeEach void setup(){String community=UUID.randomUUID().toString();manager=account(Account.Role.MANAGER,Account.Status.APPROVED,community);resident=account(Account.Role.RESIDENT,Account.Status.APPROVED,community);other=account(Account.Role.MANAGER,Account.Status.APPROVED,community+"other");otherResident=account(Account.Role.RESIDENT,Account.Status.APPROVED,community+"other");provider=account(Account.Role.PROVIDER,Account.Status.APPROVED,community);pending=account(Account.Role.RESIDENT,Account.Status.PENDING,community);}
 String account(Account.Role role,Account.Status status,String community){Account a=new Account();a.email=UUID.randomUUID()+"@test.local";a.name="Property office";a.role=role;a.status=status;a.community=community;a.passwordHash="unused";accounts.saveAndFlush(a);return a.email;}
 String body(String title,String time,Long version)throws Exception{Map<String,Object> b=new HashMap<>();b.put("title",title);b.put("publishedAt",time);b.put("content","Community update");b.put("author","Spoofed author");b.put("community","spoofed");if(version!=null)b.put("version",version);return json.writeValueAsString(b);}
 JsonNode create()throws Exception{return json.readTree(mvc.perform(post("/api/announcements").with(user(manager).roles("MANAGER")).with(csrf()).contentType("application/json").content(body("Water maintenance","2026-09-29T10:00:00Z",null))).andExpect(status().isCreated()).andExpect(jsonPath("$.author").value("Property office")).andReturn().getResponse().getContentAsString());}
 @Test void managerCrudPersistsTimeAndRejectsStaleUpdates()throws Exception{
  JsonNode item=create();long id=item.get("id").asLong(),version=item.get("version").asLong();
  mvc.perform(get("/api/announcements/"+id).with(user(resident).roles("RESIDENT"))).andExpect(status().isOk());
  var response=mvc.perform(put("/api/announcements/"+id).with(user(manager).roles("MANAGER")).with(csrf()).contentType("application/json").content(body("Updated","2026-09-30T11:00:00Z",version))).andExpect(status().isOk()).andReturn();
  long next=json.readTree(response.getResponse().getContentAsString()).get("version").asLong();assertThat(next).isGreaterThan(version);
  mvc.perform(get("/api/announcements/"+id).with(user(resident).roles("RESIDENT"))).andExpect(jsonPath("$.publishedAt").value("2026-09-30T11:00:00Z")).andExpect(jsonPath("$.title").value("Updated"));
  mvc.perform(put("/api/announcements/"+id).with(user(manager).roles("MANAGER")).with(csrf()).contentType("application/json").content(body("Stale","2026-09-30T11:00:00Z",version))).andExpect(status().isConflict());
  mvc.perform(delete("/api/announcements/"+id).param("version",""+version).with(user(manager).roles("MANAGER")).with(csrf())).andExpect(status().isConflict());
  mvc.perform(delete("/api/announcements/"+id).param("version",""+next).with(user(manager).roles("MANAGER")).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(get("/api/announcements/"+id).with(user(resident).roles("RESIDENT"))).andExpect(status().isNotFound());
 }
 @Test void anonymousAndWrongRolesCannotWrite()throws Exception{
  JsonNode item=create();long id=item.get("id").asLong();
  mvc.perform(get("/api/announcements")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/announcements").with(csrf()).contentType("application/json").content(body("Test","2026-09-29T10:00:00Z",null))).andExpect(status().isUnauthorized());
  for(String email:List.of(resident,provider,pending)){
   mvc.perform(post("/api/announcements").with(user(email)).with(csrf()).contentType("application/json").content(body("Test","2026-09-29T10:00:00Z",null))).andExpect(status().isForbidden());
   mvc.perform(put("/api/announcements/"+id).with(user(email)).with(csrf()).contentType("application/json").content(body("Test","2026-09-29T10:00:00Z",0L))).andExpect(status().isForbidden());
   mvc.perform(delete("/api/announcements/"+id).param("version","0").with(user(email)).with(csrf())).andExpect(status().isForbidden());
  }
  for(String email:List.of(provider,pending))mvc.perform(get("/api/announcements").with(user(email))).andExpect(status().isForbidden());
 }
 @Test void otherCommunityCannotReadOrMutate()throws Exception{
  JsonNode item=create();long id=item.get("id").asLong();
  for(String email:List.of(other,otherResident)){
   mvc.perform(get("/api/announcements").with(user(email))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
   mvc.perform(get("/api/announcements/"+id).with(user(email))).andExpect(status().isNotFound());
  }
  mvc.perform(put("/api/announcements/"+id).with(user(other).roles("MANAGER")).with(csrf()).contentType("application/json").content(body("Test","2026-09-29T10:00:00Z",0L))).andExpect(status().isNotFound());
  mvc.perform(delete("/api/announcements/"+id).param("version","0").with(user(other).roles("MANAGER")).with(csrf())).andExpect(status().isNotFound());
 }
 @Test void csrfAndInputValidationRemainEnabled()throws Exception{
  mvc.perform(post("/api/announcements").with(user(manager).roles("MANAGER")).contentType("application/json").content(body("Test","2026-09-29T10:00:00Z",null))).andExpect(status().isForbidden());
  mvc.perform(post("/api/announcements").with(user(manager).roles("MANAGER")).with(csrf()).contentType("application/json").content(body("  ","2026-09-29T10:00:00Z",null))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/announcements").with(user(manager).roles("MANAGER")).with(csrf()).contentType("application/json").content(body("x".repeat(161),"2026-09-29T10:00:00Z",null))).andExpect(status().isBadRequest());
 }
}
