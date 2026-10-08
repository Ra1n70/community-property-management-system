package com.cpms.community.residentchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:residentchatflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ResidentChatFlowTest {
    private static final String BASE = "/api/resident-chats/conversations";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired ResidentConversationRepository conversations;
    @Autowired ResidentChatMessageRepository messages;
    @Autowired ResidentChatService service;

    Account alice, bob, charlie, foreign, pending, rejected, manager, provider;

    @BeforeEach void setup() {
        String community = "Community " + UUID.randomUUID();
        alice = account(community, Account.Role.RESIDENT, Account.Status.APPROVED);
        bob = account(community, Account.Role.RESIDENT, Account.Status.APPROVED);
        charlie = account(community, Account.Role.RESIDENT, Account.Status.APPROVED);
        foreign = account("Community " + UUID.randomUUID(), Account.Role.RESIDENT, Account.Status.APPROVED);
        pending = account(community, Account.Role.RESIDENT, Account.Status.PENDING);
        rejected = account(community, Account.Role.RESIDENT, Account.Status.REJECTED);
        manager = account(community, Account.Role.MANAGER, Account.Status.APPROVED);
        provider = account(community, Account.Role.PROVIDER, Account.Status.APPROVED);
    }

    private Account account(String community, Account.Role role, Account.Status state) {
        Account account = new Account();
        account.email = UUID.randomUUID() + "@test.local";
        account.passwordHash = "test";
        account.name = role + " " + UUID.randomUUID();
        account.room = "101";
        account.community = community;
        account.role = role;
        account.status = state;
        return accounts.saveAndFlush(account);
    }

    private JsonNode request(Account actor, MockHttpServletRequestBuilder request, int expected) throws Exception {
        String body = mvc.perform(request.with(user(actor.email).roles(actor.role.name())).with(csrf()))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private JsonNode create(Account actor, Account target, int expected) throws Exception {
        return request(actor, post(BASE).contentType("application/json")
                .content(json.writeValueAsString(Map.of("residentId", target.id))), expected);
    }

    private JsonNode list(Account actor, int expected) throws Exception {
        return request(actor, get(BASE), expected);
    }

    private JsonNode history(Account actor, long conversationId, int expected) throws Exception {
        return request(actor, get(BASE + "/" + conversationId + "/messages"), expected);
    }

    private JsonNode send(Account actor, long conversationId, String content, String requestId, int expected) throws Exception {
        return request(actor, post(BASE + "/" + conversationId + "/messages").contentType("application/json")
                .content(json.writeValueAsString(Map.of("content", content, "clientRequestId", requestId))), expected);
    }

    private void read(Account actor, long conversationId, long messageId, int expected) throws Exception {
        request(actor, post(BASE + "/" + conversationId + "/read").contentType("application/json")
                .content(json.writeValueAsString(Map.of("throughMessageId", messageId))), expected);
    }

    @Test void pairIsCanonicalAndEmptyConversationAppearsInList() throws Exception {
        JsonNode first = create(alice, bob, 200);
        JsonNode reverse = create(bob, alice, 200);
        long id = first.get("conversationId").asLong();
        assertThat(reverse.get("conversationId").asLong()).isEqualTo(id);
        assertThat(first.get("otherResidentId").asLong()).isEqualTo(bob.id);
        assertThat(reverse.get("otherResidentId").asLong()).isEqualTo(alice.id);
        ResidentConversation stored = conversations.findById(id).orElseThrow();
        assertThat(alice.id).isLessThan(bob.id);
        assertThat(stored.community).isEqualTo(alice.community);
        assertThat(conversations.findByResidentOneAndResidentTwo(alice, bob)).isPresent();
        assertThat(list(alice, 200)).hasSize(1);
        assertThat(list(alice, 200).get(0).get("lastMessageAt").isNull()).isTrue();
        assertThat(list(alice, 200).get(0).get("unreadCount").asLong()).isZero();
        assertThat(list(charlie, 200)).isEmpty();
    }

    @Test void concurrentCreateOfSamePairLeavesOneConversation() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var fromAlice = pool.submit(() -> { start.await(); return service.create(alice.email, bob.id).conversationId(); });
            var fromBob = pool.submit(() -> { start.await(); return service.create(bob.email, alice.id).conversationId(); });
            start.countDown();
            assertThat(fromAlice.get(20, TimeUnit.SECONDS)).isEqualTo(fromBob.get(20, TimeUnit.SECONDS));
        }
        assertThat(conversations.findByResidentOneOrResidentTwo(alice, alice)).hasSize(1);
    }

    @Test void ineligibleTargetsAndCallersCannotCreateOrUseChat() throws Exception {
        create(alice, alice, 400);
        create(alice, foreign, 404);
        create(alice, pending, 404);
        create(alice, rejected, 404);
        create(pending, bob, 403);
        create(rejected, bob, 403);
        create(manager, bob, 403);
        create(provider, bob, 403);
        list(manager, 403);
        list(provider, 403);
        list(pending, 403);
        list(rejected, 403);
        assertThat(conversations.findByResidentOneOrResidentTwo(alice, alice)).isEmpty();
    }

    @Test void onlyParticipantsCanReadSendAndMarkRead() throws Exception {
        long id = create(alice, bob, 200).get("conversationId").asLong();
        JsonNode sent = send(alice, id, "Hello", "a1", 200);
        assertThat(history(bob, id, 200).get("messages").get(0).get("content").asText()).isEqualTo("Hello");
        history(alice, id, 200);
        history(charlie, id, 404);
        history(foreign, id, 404);
        send(charlie, id, "Intrusion", "c1", 404);
        send(foreign, id, "Intrusion", "f1", 404);
        read(charlie, id, sent.get("messageId").asLong(), 404);
        read(foreign, id, sent.get("messageId").asLong(), 404);
        history(manager, id, 403);
        send(provider, id, "Intrusion", "p1", 403);
        read(pending, id, sent.get("messageId").asLong(), 403);
        assertThat(history(alice, id, 200).get("messages").size()).isEqualTo(1);
    }

    @Test void messagesAreOrderedPaginatedValidatedAndIdempotent() throws Exception {
        long id = create(alice, bob, 200).get("conversationId").asLong();
        List<Long> ids = new ArrayList<>();
        ids.add(send(alice, id, "Hello", "a1", 200).get("messageId").asLong());
        ids.add(send(bob, id, "Hi", "b1", 200).get("messageId").asLong());
        for (int i = 2; i <= 4; i++) ids.add(send(alice, id, "Message " + i, "a" + i, 200).get("messageId").asLong());
        JsonNode page = request(bob, get(BASE + "/" + id + "/messages").param("size", "2"), 200);
        assertThat(messageIds(page)).containsExactly(ids.get(3), ids.get(4));
        JsonNode earlier = request(bob, get(BASE + "/" + id + "/messages")
                .param("size", "2").param("beforeId", page.get("nextBeforeId").asText()), 200);
        assertThat(messageIds(earlier)).containsExactly(ids.get(1), ids.get(2));
        JsonNode oldest = request(bob, get(BASE + "/" + id + "/messages")
                .param("size", "2").param("beforeId", earlier.get("nextBeforeId").asText()), 200);
        assertThat(messageIds(oldest)).containsExactly(ids.get(0));
        assertThat(oldest.get("nextBeforeId").isNull()).isTrue();
        assertThat(history(alice, id, 200).get("messages").get(1).get("senderId").asLong()).isEqualTo(bob.id);
        assertThat(history(bob, id, 200).get("messages").get(0).get("senderName").asText()).isEqualTo(alice.name);

        assertThat(send(alice, id, "Hello", "a1", 200).get("messageId").asLong()).isEqualTo(ids.get(0));
        assertThat(send(alice, id, "Different", "a1", 409).get("message").asText()).contains("request ID");
        send(alice, id, "   ", "blank", 400);
        send(alice, id, "x".repeat(1001), "too-long", 400);
        send(alice, id, "Valid", " ", 400);
        request(alice, get(BASE + "/" + id + "/messages").param("size", "101"), 400);
        assertThat(history(alice, id, 200).get("messages").size()).isEqualTo(5);
        assertThat(history(alice, id, 200).get("messages").get(0).get("content").asText()).isEqualTo("Hello");
    }

    @Test void listShowsOnlyOwnChatsNewestFirstAndHandlesEmptyChat() throws Exception {
        long withBob = create(alice, bob, 200).get("conversationId").asLong();
        long withCharlie = create(alice, charlie, 200).get("conversationId").asLong();
        send(alice, withCharlie, "First", "a1", 200);
        JsonNode list = list(alice, 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("conversationId").asLong()).isEqualTo(withCharlie);
        assertThat(list.get(1).get("conversationId").asLong()).isEqualTo(withBob);
        assertThat(list.get(1).get("lastMessageContent").isNull()).isTrue();
        send(bob, withBob, "Later", "b1", 200);
        list = list(alice, 200);
        assertThat(list.get(0).get("conversationId").asLong()).isEqualTo(withBob);
        assertThat(list.get(0).get("unreadCount").asLong()).isEqualTo(1);
        assertThat(list(bob, 200)).hasSize(1);
        assertThat(list(foreign, 200)).isEmpty();
    }

    @Test void readPositionsAreIndependentMonotonicAndConversationScoped() throws Exception {
        long id = create(alice, bob, 200).get("conversationId").asLong();
        long otherId = create(alice, charlie, 200).get("conversationId").asLong();
        long first = send(alice, id, "From Alice", "a1", 200).get("messageId").asLong();
        long second = send(bob, id, "From Bob", "b1", 200).get("messageId").asLong();
        long third = send(alice, id, "Again", "a2", 200).get("messageId").asLong();
        long elsewhere = send(alice, otherId, "Elsewhere", "a3", 200).get("messageId").asLong();

        assertThat(history(alice, id, 200).get("unreadCount").asLong()).isEqualTo(1);
        assertThat(history(bob, id, 200).get("unreadCount").asLong()).isEqualTo(2);
        read(alice, id, second, 204);
        assertThat(history(alice, id, 200).get("unreadCount").asLong()).isZero();
        assertThat(history(bob, id, 200).get("unreadCount").asLong()).isEqualTo(2);
        read(bob, id, third, 204);
        read(alice, id, first, 204);
        read(bob, id, first, 204);
        read(alice, id, elsewhere, 404);
        ResidentConversation stored = conversations.findById(id).orElseThrow();
        assertThat(stored.residentOneLastReadMessageId).isEqualTo(second);
        assertThat(stored.residentTwoLastReadMessageId).isEqualTo(third);
        assertThat(history(bob, id, 200).get("unreadCount").asLong()).isZero();
        send(alice, id, "New", "a4", 200);
        assertThat(history(bob, id, 200).get("unreadCount").asLong()).isEqualTo(1);
    }

    private List<Long> messageIds(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.get("messages").forEach(message -> result.add(message.get("messageId").asLong()));
        return result;
    }
}
