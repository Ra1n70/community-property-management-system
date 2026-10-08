package com.cpms.community;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"locker.pickup-code-secret=integration-test-only-not-for-deployment","spring.datasource.url=jdbc:h2:mem:RecoveryFlowTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","demo.invite-code=test-invite","demo.manager-password=TestManager123!"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class RecoveryFlowTest {
    @Autowired MockMvc mvc;@Autowired AccountRepository accounts;@Autowired RecoveryLinkRepository links;
    @Autowired RecoveryService recovery;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    Account resident,manager,provider;
    @BeforeEach void reset(){links.deleteAll();accounts.deleteAll();resident=seed("resident@demo.test",Account.Role.RESIDENT,"A");manager=seed("manager@demo.test",Account.Role.MANAGER,"A");provider=seed("provider@demo.test",Account.Role.PROVIDER,"A");}
    Account seed(String email,Account.Role role,String community){Account a=new Account();a.email=email;a.name="Test";a.role=role;a.community=community;a.status=Account.Status.PENDING;a.passwordHash=encoder.encode("OriginalPass123!");return accounts.save(a);}
    String body(Object... pairs)throws Exception{Map<String,Object> m=new HashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return json.writeValueAsString(m);}
    MockHttpSession login(Account a,String role)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("email",a.email).param("password","OriginalPass123!").param("role",role)).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);}
    @Test void wrongPortalDoesNotLeaveAnAuthenticatedSession()throws Exception{
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("email",manager.email).param("password","OriginalPass123!").param("role","RESIDENT")).andExpect(status().isForbidden()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        login(manager,"MANAGER");login(provider,"PROVIDER");
    }
    @Test void passwordChangeRequiresOldPasswordAndInvalidatesEverySession()throws Exception{
        MockHttpSession first=login(resident,"RESIDENT"),second=login(resident,"RESIDENT");
        mvc.perform(post("/api/auth/password").session(first).with(csrf()).contentType("application/json").content(body("currentPassword","wrong","newPassword","NextPassword123!","confirmation","NextPassword123!"))).andExpect(status().isBadRequest());
        String code=recovery.issue(resident.email,null);
        mvc.perform(post("/api/auth/password").session(first).with(csrf()).contentType("application/json").content(body("currentPassword","OriginalPass123!","newPassword","NextPassword123!","confirmation","NextPassword123!"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(first)).andExpect(status().isUnauthorized());
        assertThat(accounts.findById(resident.id).orElseThrow().recoveryHash).isEqualTo(RecoveryService.hash(code));
    }
    @Test void recoveryCodeIsHashedSingleUseAndKeepsApprovalState()throws Exception{
        String code=recovery.issue(resident.email,null);
        assertThat(accounts.findById(resident.id).orElseThrow().recoveryHash).isNotEqualTo(code);
        mvc.perform(post("/api/auth/recover").with(csrf()).contentType("application/json").content(body("email",resident.email,"code",code,"newPassword","NextPassword123!","confirmation","NextPassword123!"))).andExpect(status().isOk()).andExpect(jsonPath("$.recoveryCode").isNotEmpty());
        assertThat(accounts.findById(resident.id).orElseThrow().status).isEqualTo(Account.Status.PENDING);
        assertThatThrownBy(()->recovery.recover(resident.email,code,"AnotherPass123!","AnotherPass123!")).hasMessageContaining("incorrect");
    }
    @Test void replacingCodeRequiresPasswordAndInvalidatesOldCode(){
        String first=recovery.issue(provider.email,null);
        assertThatThrownBy(()->recovery.issue(provider.email,null)).hasMessageContaining("incorrect");
        String second=recovery.issue(provider.email,"OriginalPass123!");
        assertThat(first).isNotEqualTo(second);
        assertThatThrownBy(()->recovery.recover(provider.email,first,"NextPassword123!","NextPassword123!")).hasMessageContaining("incorrect");
    }
    @Test void assistedRecoveryEnforcesOwnershipAndIdentityVerification()throws Exception{
        Account other=seed("other@demo.test",Account.Role.RESIDENT,"B");
        assertThatThrownBy(()->recovery.createLink(manager.email,other.id,"Verified offline",true)).hasMessageContaining("not found");
        assertThatThrownBy(()->recovery.createLink(manager.email,manager.id,"Verified offline",true)).hasMessageContaining("not found");
        assertThatThrownBy(()->recovery.createLink(manager.email,resident.id,"",true)).hasMessageContaining("Confirm identity");
        mvc.perform(post("/api/manager/accounts/"+resident.id+"/recovery").with(user(resident.email).roles("RESIDENT")).with(csrf()).contentType("application/json").content(body("note","Verified offline","verified",true))).andExpect(status().isForbidden());
        String token=recovery.createLink(manager.email,provider.id,"Verified contract contact",true).token();
        assertThat(links.findByTokenHash(RecoveryService.hash(token))).isPresent();
        recovery.redeem(token,"NextPassword123!","NextPassword123!");
        assertThat(recovery.history(manager.email,provider.id).getFirst().status()).isEqualTo("COMPLETED");
        assertThatThrownBy(()->recovery.redeem(token,"AnotherPass123!","AnotherPass123!")).hasMessageContaining("invalid or expired");
    }
    @Test void newLinksRevokeOldLinksAndExpiredOrRevokedLinksFail(){
        String old=recovery.createLink(manager.email,resident.id,"Verified resident",true).token();
        String next=recovery.createLink(manager.email,resident.id,"Verified resident again",true).token();
        assertThatThrownBy(()->recovery.redeem(old,"NextPassword123!","NextPassword123!")).hasMessageContaining("invalid or expired");
        RecoveryLink link=links.findByTokenHash(RecoveryService.hash(next)).orElseThrow();link.expiresAt=Instant.now().minusSeconds(1);links.save(link);
        assertThatThrownBy(()->recovery.redeem(next,"NextPassword123!","NextPassword123!")).hasMessageContaining("invalid or expired");
        String third=recovery.createLink(manager.email,resident.id,"Verified",true).token();recovery.revoke(manager.email,resident.id);
        assertThatThrownBy(()->recovery.redeem(third,"NextPassword123!","NextPassword123!")).hasMessageContaining("invalid or expired");
    }
    @Test void linkStatusReportsReplacedRevokedExpiredAndUsedLinksAsInvalid()throws Exception{
        String old=recovery.createLink(manager.email,resident.id,"Verified resident",true).token();
        assertThat(recovery.status(old).valid()).isTrue();assertThat(recovery.status(old).expiresAt()).isAfter(Instant.now().plusSeconds(1700));
        String next=recovery.createLink(manager.email,resident.id,"Verified again",true).token();
        assertThat(recovery.status(old).valid()).isFalse();assertThat(recovery.status(old).expiresAt()).isNull();
        mvc.perform(post("/api/auth/recovery-link/status").with(csrf()).contentType("application/json").content(body("token",next)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/auth/recovery-link/status").with(csrf()).contentType("application/json").content(body("token",old)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/auth/recovery-link/status").with(csrf()).contentType("application/json").content(body("token","not-a-real-token")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
        recovery.revoke(manager.email,resident.id);assertThat(recovery.status(next).valid()).isFalse();
        String third=recovery.createLink(manager.email,resident.id,"Verified",true).token();
        RecoveryLink link=links.findByTokenHash(RecoveryService.hash(third)).orElseThrow();link.expiresAt=Instant.now().minusSeconds(1);links.save(link);
        assertThat(recovery.status(third).valid()).isFalse();
        String fourth=recovery.createLink(manager.email,resident.id,"Verified",true).token();recovery.redeem(fourth,"NextPassword123!","NextPassword123!");
        assertThat(recovery.status(fourth).valid()).isFalse();
    }
    @Test void simultaneousRedemptionOnlySucceedsOnce()throws Exception{
        String token=recovery.createLink(manager.email,resident.id,"Verified",true).token();
        try(var pool=Executors.newFixedThreadPool(2)){
            CountDownLatch start=new CountDownLatch(1);
            Callable<Boolean> task=()->{start.await();try{recovery.redeem(token,"NextPassword123!","NextPassword123!");return true;}catch(org.springframework.web.server.ResponseStatusException e){return false;}};
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void recoveryRequiresCsrfAndRateLimitsAttempts()throws Exception{
        mvc.perform(post("/api/auth/recover").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for(int i=0;i<21;i++)mvc.perform(post("/api/auth/recover").servletPath("/api/auth/recover").with(csrf()).with(r->{r.setRemoteAddr("192.0.2.19");return r;}).contentType("application/json").content("{}"))
            .andExpect(status().is(i<20?400:429));
    }
}
