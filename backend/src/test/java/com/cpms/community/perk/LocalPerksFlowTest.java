package com.cpms.community.perk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.perk.repository.PerkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:LocalPerksFlowTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=integration-test-only-not-for-deployment",
        "demo.invite-code=test-invite",
        "demo.manager-password=TestManager123!"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class LocalPerksFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant PAST_START = NOW.minus(10, ChronoUnit.DAYS);
    private static final Instant PAST_END = NOW.minus(1, ChronoUnit.DAYS);
    private static final Instant FUTURE_START = NOW.plus(7, ChronoUnit.DAYS);
    private static final Instant FUTURE_END = NOW.plus(60, ChronoUnit.DAYS);

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired PerkRepository perks;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void reset() {
        perks.deleteAll();
        accounts.deleteAll();
        account("resident@test.local", Account.Role.RESIDENT, Account.Status.APPROVED, "Demo Community");
        account("manager@test.local", Account.Role.MANAGER, Account.Status.APPROVED, "Demo Community");
        account("pending@test.local", Account.Role.RESIDENT, Account.Status.PENDING, "Demo Community");
        account("other@test.local", Account.Role.RESIDENT, Account.Status.APPROVED, "Other Community");
        account("manager.other@test.local", Account.Role.MANAGER, Account.Status.APPROVED, "Other Community");
    }

    @Test
    void localPerksP0Flow() throws Exception {
        mvc.perform(get("/api/perks").with(user("pending@test.local").roles("RESIDENT")))
                .andExpect(status().isForbidden());

        MvcResult otherResult = mvc.perform(post("/api/manager/perks")
                        .with(user("manager.other@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Other Cafe", "Other community perk", "DINING", PAST_START, FUTURE_END)))
                .andExpect(status().isCreated())
                .andReturn();
        Long otherId = readId(otherResult);
        mvc.perform(get("/api/perks/" + otherId).with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/manager/perks").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Resident Cafe", "Residents cannot create perks", "DINING", PAST_START, FUTURE_END)))
                .andExpect(status().isForbidden());

        MvcResult perkResult = mvc.perform(post("/api/manager/perks")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Sunny Bakery", "10% off fresh bread", "DINING", PAST_START, FUTURE_END)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.published").value(true))
                .andReturn();
        Long perkId = readId(perkResult);

        mvc.perform(get("/api/perks").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + perkId + ")]").exists());

        mvc.perform(get("/api/perks?category=DINING").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + perkId + ")]").exists());
        mvc.perform(get("/api/perks?category=SHOPPING").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + perkId + ")]").isEmpty());

        mvc.perform(get("/api/perks/" + perkId).with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Sunny Bakery"));

        Long expiredId = readId(mvc.perform(post("/api/manager/perks")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Old Diner", "Last year's deal", "DINING", PAST_START, PAST_END)))
                .andExpect(status().isCreated())
                .andReturn());
        mvc.perform(get("/api/perks").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + expiredId + ")]").isEmpty());
        mvc.perform(get("/api/perks?status=expired").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + expiredId + ")]").exists());

        Long upcomingId = readId(mvc.perform(post("/api/manager/perks")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Future Fitness", "New year membership offer", "HEALTH", FUTURE_START, FUTURE_END)))
                .andExpect(status().isCreated())
                .andReturn());
        mvc.perform(get("/api/perks?status=upcoming").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + upcomingId + ")]").exists());
        mvc.perform(get("/api/perks?status=all").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + perkId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + expiredId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + upcomingId + ")]").exists());

        mvc.perform(get("/api/perks?status=bogus").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/perks?category=UNKNOWN").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/manager/perks")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Bad Window Cafe", "End before start", "DINING", FUTURE_START, PAST_END)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/manager/perks")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Bad Category Cafe", "Unknown category", "UNKNOWN", PAST_START, FUTURE_END)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/manager/perks/" + perkId + "/publish")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/perks").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$[?(@.id == " + perkId + ")]").isEmpty());
        mvc.perform(get("/api/perks/" + perkId).with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/manager/perks?status=all").with(user("manager@test.local").roles("MANAGER")))
                .andExpect(jsonPath("$[?(@.id == " + perkId + " && @.published == false)]").exists());

        mvc.perform(put("/api/manager/perks/" + perkId)
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perkJson("Sunny Bakery", "15% off fresh bread", "DINING", PAST_START, FUTURE_END)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("15% off fresh bread"));

        mvc.perform(post("/api/manager/perks/" + perkId + "/publish")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/perks/" + perkId).with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("15% off fresh bread"));

        mvc.perform(get("/api/perks?status=all&sort=ending").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/manager/perks/" + expiredId)
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/manager/perks/" + expiredId).with(user("manager@test.local").roles("MANAGER")))
                .andExpect(status().isNotFound());
    }

    private Account account(String email, Account.Role role, Account.Status status, String community) {
        Account account = new Account();
        account.email = email;
        account.name = role.name() + " User";
        account.room = "101";
        account.passwordHash = encoder.encode("TestPassword123!");
        account.role = role;
        account.status = status;
        account.community = community;
        return accounts.save(account);
    }

    private String perkJson(String business, String title, String category, Instant start, Instant end) throws Exception {
        HashMap<String, Object> body = new HashMap<>();
        body.put("businessName", business);
        body.put("title", title);
        body.put("description", "Show this perk at the counter.");
        body.put("category", category);
        body.put("contact", "555-0100");
        body.put("address", "10 Main Street");
        body.put("website", "https://example.com");
        body.put("startAt", start);
        body.put("endAt", end);
        return JSON.writeValueAsString(body);
    }

    private Long readId(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }
}
