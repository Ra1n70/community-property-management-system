package com.cpms.community;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"locker.pickup-code-secret=integration-test-only-not-for-deployment","spring.datasource.url=jdbc:h2:mem:ManagerFlowTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","demo.invite-code=test-invite","demo.manager-password=TestManager123!"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class ManagerFlowTest {
 @Autowired MockMvc mvc; @Autowired AccountRepository accounts; @Autowired CommunitySettingRepository settings;
 @Autowired RoomChangeRepository changes; @Autowired RecoveryLinkRepository links; @Autowired PasswordEncoder encoder;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 Account manager,resident,other;
 @BeforeEach void reset(){links.deleteAll();changes.deleteAll();settings.deleteAll();accounts.deleteAll();manager=seed("manager@test.local",Account.Role.MANAGER,"Demo Community");resident=seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");other=seed("other@test.local",Account.Role.RESIDENT,"Other");}
 @AfterEach void cleanup(){settings.deleteAll();}
 Account seed(String email,Account.Role role,String community){Account a=new Account();a.email=email;a.name="Test";a.passwordHash=encoder.encode("OriginalPass123!");a.role=role;a.community=community;a.status=Account.Status.APPROVED;a.room="101";return accounts.save(a);}
 @Test void inviteRotationActuallyChangesRegistration()throws Exception{
  mvc.perform(put("/api/manager/invite-code").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"code\":\"new-invite\"}")).andExpect(status().isOk());
  String registration="{\"name\":\"New\",\"email\":\"new@test.local\",\"password\":\"NewPassword123!\",\"room\":\"102\",\"inviteCode\":\"%s\"}";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content(registration.formatted("test-invite"))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content(registration.formatted("new-invite"))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
  assertThat(accounts.findById(resident.id).orElseThrow().status).isEqualTo(Account.Status.APPROVED);
 }
 @Test void roomChangeUpdatesAccountAndKeepsHistoryAndStatus()throws Exception{
  mvc.perform(put("/api/manager/residents/"+resident.id+"/room").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"room\":\"202\",\"reason\":\"Verified move\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.room").value("202"));
  assertThat(accounts.findById(resident.id).orElseThrow().room).isEqualTo("202");
  var event=changes.findByAccountIdOrderByCreatedAtDesc(resident.id).getFirst();assertThat(event.oldRoom).isEqualTo("101");assertThat(event.newRoom).isEqualTo("202");assertThat(event.managerId).isEqualTo(manager.id);assertThat(event.reason).isEqualTo("Verified move");
  assertThat(accounts.findById(resident.id).orElseThrow().status).isEqualTo(Account.Status.APPROVED);
  mvc.perform(get("/api/manager/residents/"+other.id+"/room-history").with(user(manager.email).roles("MANAGER"))).andExpect(status().isNotFound());
  mvc.perform(put("/api/manager/residents/"+other.id+"/room").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"room\":\"202\",\"reason\":\"Move\"}")).andExpect(status().isNotFound());
 }
 @Test void providerSetupAllowsOwnPasswordAndProviderLoginOnly()throws Exception{
  var result=mvc.perform(post("/api/manager/providers").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"name\":\"Plumber\",\"email\":\"PROVIDER@test.local\",\"providerType\":\"MAINTENANCE\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.account.role").value("PROVIDER")).andExpect(jsonPath("$.account.passwordHash").doesNotExist()).andReturn();
  String token=json.readTree(result.getResponse().getContentAsString()).path("setup").path("token").asText();
  mvc.perform(post("/api/auth/recovery-link").with(csrf()).contentType("application/json").content("{\"token\":\""+token+"\",\"newPassword\":\"ProviderPass123!\",\"confirmation\":\"ProviderPass123!\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.recoveryCode").isNotEmpty());
  mvc.perform(post("/api/auth/login").with(csrf()).param("email","provider@test.local").param("password","ProviderPass123!").param("role","PROVIDER")).andExpect(status().isNoContent());
  mvc.perform(post("/api/auth/login").with(csrf()).param("email","provider@test.local").param("password","ProviderPass123!").param("role","RESIDENT")).andExpect(status().isForbidden());
  mvc.perform(post("/api/manager/providers").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"name\":\"Duplicate\",\"email\":\"provider@test.local\",\"providerType\":\"MAINTENANCE\"}")).andExpect(status().isConflict());
 }
 @Test void managementRejectsNonManagersMissingCsrfAndBlankInputs()throws Exception{
  mvc.perform(get("/api/manager/invite-code").with(user(resident.email).roles("RESIDENT"))).andExpect(status().isForbidden());
  mvc.perform(post("/api/manager/providers").with(user(resident.email).roles("RESIDENT")).with(csrf()).contentType("application/json").content("{\"name\":\"Test\",\"email\":\"p@test.local\"}")).andExpect(status().isForbidden());
  mvc.perform(put("/api/manager/invite-code").with(user(manager.email).roles("MANAGER")).contentType("application/json").content("{\"code\":\"new\"}")).andExpect(status().isForbidden());
  mvc.perform(put("/api/manager/invite-code").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"code\":\"   \"}")).andExpect(status().isBadRequest());
  mvc.perform(put("/api/manager/residents/"+resident.id+"/room").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"room\":\" \",\"reason\":\"Move\"}")).andExpect(status().isBadRequest());
 }
 @Test void roomChangeReasonIsOptional()throws Exception{
  mvc.perform(put("/api/manager/residents/"+resident.id+"/room").with(user(manager.email).roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"room\":\"203\",\"reason\":\" \"}")).andExpect(status().isOk()).andExpect(jsonPath("$.room").value("203"));
  mvc.perform(get("/api/manager/residents/"+resident.id+"/room-history").with(user(manager.email).roles("MANAGER"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].newRoom").value("203")).andExpect(jsonPath("$[0].reason").doesNotExist());
 }
}
