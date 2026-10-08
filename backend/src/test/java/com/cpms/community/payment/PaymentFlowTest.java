package com.cpms.community.payment;

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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:paymentflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","locker.pickup-code-secret=test-only","demo.manager-password=","demo.invite-code=test"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class PaymentFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    Account manager,resident,other,pending,provider;
    @BeforeEach void setup(){String community=UUID.randomUUID().toString();manager=account(community,Account.Role.MANAGER,Account.Status.APPROVED);resident=account(community,Account.Role.RESIDENT,Account.Status.APPROVED);other=account(UUID.randomUUID().toString(),Account.Role.RESIDENT,Account.Status.APPROVED);pending=account(community,Account.Role.RESIDENT,Account.Status.PENDING);provider=account(community,Account.Role.PROVIDER,Account.Status.APPROVED);}
    Account account(String community,Account.Role role,Account.Status status){Account a=new Account();a.email=UUID.randomUUID()+"@test.local";a.name="Test user";a.passwordHash="test";a.community=community;a.room="101";a.role=role;a.status=status;return accounts.saveAndFlush(a);}
    JsonNode call(Account a,MockHttpServletRequestBuilder req,Object body,int expected)throws Exception{
        if(a!=null)req.with(user(a.email).roles(a.role.name()));
        req.with(csrf());if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
        String response=mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.nullNode():json.readTree(response);
    }



    JsonNode bill()throws Exception{return call(manager,post("/api/payments"),Map.of("residentId",resident.id,"title","Monthly fee","description","Demo bill","amount","12.34","dueDate",LocalDate.now().plusDays(10).toString()),201);}
    @Test void paymentIsOwnedAndIdempotent()throws Exception{
        JsonNode bill=bill();String pay="/api/payments/"+bill.get("id").asLong()+"/pay";
        assertThat(call(resident,get("/api/payments"),null,200).size()).isEqualTo(1);
        Account neighbor=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);
        assertThat(call(neighbor,get("/api/payments"),null,200).size()).isZero();
        call(neighbor,post(pay),Map.of("confirmed",true),404);call(other,post(pay),Map.of("confirmed",true),404);call(manager,post(pay),Map.of("confirmed",true),403);
        call(resident,post(pay),Map.of("confirmed",false),400);
        JsonNode receipt=call(resident,post(pay),Map.of("confirmed",true,"amount","0.01"),200);
        assertThat(receipt.get("status").asText()).isEqualTo("PAID");assertThat(receipt.get("amount").decimalValue()).isEqualByComparingTo("12.34");
        JsonNode again=call(resident,post(pay),Map.of("confirmed",true),200);
        assertThat(again.get("receiptNumber")).isEqualTo(receipt.get("receiptNumber"));assertThat(again.get("paidAt")).isEqualTo(receipt.get("paidAt"));
    }
    @Test void accessAndValidation()throws Exception{
        call(null,get("/api/payments"),null,401);call(provider,get("/api/payments"),null,403);call(pending,get("/api/payments"),null,403);
        call(resident,get("/api/payments/residents"),null,403);
        Map<String,Object> data=new HashMap<>(Map.of("residentId",resident.id,"title","Bill","amount","10.00","dueDate",LocalDate.now().plusDays(2).toString()));
        call(resident,post("/api/payments"),data,403);
        for(String amount:List.of("0","-1","1.001","10000000")){data.put("amount",amount);call(manager,post("/api/payments"),data,400);}
        data.put("amount","10.00");data.put("residentId",other.id);call(manager,post("/api/payments"),data,400);
        data.put("residentId",resident.id);data.put("dueDate","2020-01-01");call(manager,post("/api/payments"),data,400);
        JsonNode b=bill();mvc.perform(post("/api/payments/"+b.get("id").asLong()+"/pay").with(user(resident.email).roles("RESIDENT")).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());
    }
}
