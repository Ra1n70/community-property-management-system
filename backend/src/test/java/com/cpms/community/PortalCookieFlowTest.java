package com.cpms.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:PortalCookieFlowTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test"})
@ActiveProfiles("demo")
class PortalCookieFlowTest {
    @LocalServerPort int port;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    private final HttpClient browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    private HttpResponse<String> request(String path,String role,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api"+path))
            .header("X-Community-Portal",role);
        if(body!=null) {
            var csrf=json.readTree(request("/auth/csrf",role,null).body());
            request.header(csrf.get("headerName").asText(),csrf.get("token").asText())
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return browser.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void sharedBrowserCookieSurvivesLoginRotationAndIndependentLogout() throws Exception {
        for(Account.Role role:new Account.Role[]{Account.Role.RESIDENT,Account.Role.MANAGER}) {
            Account account=new Account();account.email=role.name().toLowerCase(java.util.Locale.ROOT)+"@cookie.test";account.name=role.name();
            account.role=role;account.community="Cookie Test";account.room="101";
            account.status=Account.Status.APPROVED;account.passwordHash=encoder.encode("TestPassword123!");
            accounts.saveAndFlush(account);
            assertThat(request("/auth/login",role.name(),"email="+role+"%40cookie.test&password=TestPassword123!&role="+role).statusCode()).isEqualTo(204);
        }
        for(String role:new String[]{"RESIDENT","MANAGER"}) {
            var response=request("/auth/me",role,null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("role").asText()).isEqualTo(role);
        }
        assertThat(request("/manager/applications","MANAGER",null).statusCode()).isEqualTo(200);
        assertThat(request("/manager/applications","RESIDENT",null).statusCode()).isEqualTo(403);
        assertThat(request("/auth/logout","RESIDENT","").statusCode()).isEqualTo(204);
        assertThat(request("/auth/me","RESIDENT",null).statusCode()).isEqualTo(401);
        assertThat(request("/auth/me","MANAGER",null).statusCode()).isEqualTo(200);
    }
    @Test void signingInAgainOnTheSameEntranceSwitchesAccount() throws Exception {
        for(String name:new String[]{"first","second"}) {
            Account account=new Account();account.email=name+"@switch.test";account.name=name;
            account.role=Account.Role.RESIDENT;account.community="Cookie Test";account.room="101";
            account.status=Account.Status.APPROVED;account.passwordHash=encoder.encode("TestPassword123!");
            accounts.saveAndFlush(account);
            assertThat(request("/auth/login","RESIDENT","email="+name+"%40switch.test&password=TestPassword123!&role=RESIDENT").statusCode()).isEqualTo(204);
            var me=request("/auth/me","RESIDENT",null);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body()).get("email").asText()).isEqualTo(name+"@switch.test");
        }
    }
}
