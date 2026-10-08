package com.cpms.community;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"locker.pickup-code-secret=integration-test-only-not-for-deployment","spring.datasource.url=jdbc:h2:mem:AuthenticationFlowTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","demo.invite-code=test-invite","demo.manager-password=TestManager123!"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class AuthenticationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired ReviewRepository reviews;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void reset() {reviews.deleteAll();accounts.deleteAll();seed("manager@test.local",Account.Role.MANAGER,"Demo Community");}
    private Account seed(String email,Account.Role role,String community) {
        Account a=new Account();a.name="Test User";a.email=email;a.room="101";a.role=role;a.community=community;
        a.status=role==Account.Role.RESIDENT?Account.Status.PENDING:Account.Status.APPROVED;
        a.passwordHash=encoder.encode("TestPassword123!");return accounts.save(a);
    }
    @Test void registrationUsesServerRoleAndHashesPassword() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content("{\"name\":\"Resident\",\"email\":\"Resident@EXAMPLE.com\",\"password\":\"TestPassword123!\",\"room\":\"101\",\"inviteCode\":\"test-invite\",\"role\":\"MANAGER\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("RESIDENT")).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(encoder.matches("TestPassword123!",accounts.findByEmail("resident@example.com").orElseThrow().passwordHash)).isTrue();
    }
    @Test void missingCsrfAndAnonymousAccessAreBlocked() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void rejectResubmitApproveAndProtectBusinessEndpoint() throws Exception {
        Account resident=seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");
        mvc.perform(get("/api/resident/welcome").with(user(resident.email).roles("RESIDENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/manager/applications/"+resident.id+"/review").with(user("manager@test.local").roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"status\":\"REJECTED\",\"reason\":\"Correct your room\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/resubmit").with(user(resident.email).roles("RESIDENT")).with(csrf()).contentType("application/json").content("{\"name\":\"Resident\",\"room\":\"102\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/manager/applications/"+resident.id+"/review").with(user("manager@test.local").roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"status\":\"APPROVED\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/resident/welcome").with(user(resident.email).roles("RESIDENT"))).andExpect(status().isOk());
        assertThat(reviews.findByAccountIdOrderByCreatedAtDesc(resident.id)).hasSize(2);
    }
    @Test void duplicateReviewAndBlankRejectionFail() throws Exception {
        Account a=seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");String url="/api/manager/applications/"+a.id+"/review";
        mvc.perform(post(url).with(user("manager@test.local").roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"status\":\"REJECTED\",\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(user("manager@test.local").roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"status\":\"APPROVED\"}")).andExpect(status().isOk());
        mvc.perform(post(url).with(user("manager@test.local").roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"status\":\"REJECTED\",\"reason\":\"Late review\"}")).andExpect(status().isConflict());
    }
    @Test void otherCommunityAndWrongRolesAreBlocked() throws Exception {
        Account a=seed("other@test.local",Account.Role.RESIDENT,"Other Community");
        mvc.perform(post("/api/manager/applications/"+a.id+"/review").with(user("manager@test.local").roles("MANAGER")).with(csrf()).contentType("application/json").content("{\"status\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/manager/applications").with(user(a.email).roles("RESIDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/manager/applications").with(user("provider@test.local").roles("PROVIDER"))).andExpect(status().isForbidden());
    }
    @Test void loginSessionAndLogoutWork() throws Exception {
        seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("email","resident@test.local").param("password","TestPassword123!")).andExpect(status().isNoContent()).andReturn();
        MockHttpSession session=(MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("resident@test.local"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession portalLogin(MockHttpSession session, String role, String email) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").session(session).with(csrf())
            .header("X-Community-Portal",role).param("role",role).param("email",email)
            .param("password","TestPassword123!")).andExpect(status().isNoContent())
            .andReturn().getRequest().getSession(false);
    }
    @Test void portalsKeepSeparateIdentitiesAndLogoutOnlyTheSelectedRole() throws Exception {
        seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");
        seed("provider@test.local",Account.Role.PROVIDER,"Demo Community");
        MockHttpSession session=portalLogin(new MockHttpSession(),"MANAGER","manager@test.local");
        portalLogin(session,"RESIDENT","resident@test.local");
        portalLogin(session,"PROVIDER","provider@test.local");
        for(String role: new String[]{"MANAGER","RESIDENT","PROVIDER"}) {
            mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal",role))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role));
        }
        mvc.perform(get("/api/manager/applications").session(session).header("X-Community-Portal","MANAGER"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/manager/applications").session(session).header("X-Community-Portal","RESIDENT"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session).param("portal","MANAGER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()).header("X-Community-Portal","RESIDENT"))
            .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","RESIDENT"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","MANAGER"))
            .andExpect(status().isOk());
        portalLogin(session,"RESIDENT","resident@test.local");
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()).header("X-Community-Portal","MANAGER"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","RESIDENT"))
            .andExpect(status().isOk());
    }
    @Test void portalSelectorCannotCreateManagerAuthentication() throws Exception {
        seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");
        MockHttpSession session=portalLogin(new MockHttpSession(),"RESIDENT","resident@test.local");
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","MANAGER"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).header("X-Community-Portal","MANAGER")
            .param("role","RESIDENT").param("email","resident@test.local").param("password","TestPassword123!"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","MANAGER"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","RESIDENT"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","INVALID"))
            .andExpect(status().isUnauthorized());
    }
    @Test void passwordRevocationDoesNotSignOutOtherPortals() throws Exception {
        Account resident=seed("resident@test.local",Account.Role.RESIDENT,"Demo Community");
        MockHttpSession session=portalLogin(new MockHttpSession(),"RESIDENT",resident.email);
        portalLogin(session,"MANAGER","manager@test.local");
        resident.sessionVersion="changed-version";accounts.saveAndFlush(resident);
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","RESIDENT"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(session).header("X-Community-Portal","MANAGER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"));
    }
}
