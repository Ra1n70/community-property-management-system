package com.cpms.community.directmessage;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:directmessagingread;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DirectMessagingReadTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired DirectConversationRepository conversations;

    Account resident, manager, managerTwo, foreignManager, provider, pending, rejected;

    @BeforeEach void setup() {
        String community = "Community " + UUID.randomUUID();
        resident = account(community, Account.Role.RESIDENT, Account.Status.APPROVED);
        manager = account(community, Account.Role.MANAGER, Account.Status.APPROVED);
        managerTwo = account(community, Account.Role.MANAGER, Account.Status.APPROVED);
        foreignManager = account("Community " + UUID.randomUUID(), Account.Role.MANAGER, Account.Status.APPROVED);
        provider = account(community, Account.Role.PROVIDER, Account.Status.APPROVED);
        pending = account(community, Account.Role.RESIDENT, Account.Status.PENDING);
        rejected = account(community, Account.Role.RESIDENT, Account.Status.REJECTED);
    }

    private Account account(String community, Account.Role role, Account.Status status) {
        Account account = new Account();
        account.email = UUID.randomUUID() + "@test.local";
        account.passwordHash = "test";
        account.name = role.name() + " user";
        account.room = "101";
        account.community = community;
        account.role = role;
        account.status = status;
        return accounts.saveAndFlush(account);
    }

    private String residentPath() { return "/api/direct-messages/me/messages"; }
    private String managerListPath() { return "/api/direct-messages/conversations"; }
    private String managerMessagesPath(long id) { return managerListPath() + "/" + id + "/messages"; }
    private String managerReadPath(long id) { return managerListPath() + "/" + id + "/read"; }

    private JsonNode send(Account actor, String path, String content, String requestId) throws Exception {
        String body = json.writeValueAsString(Map.of("content", content, "clientRequestId", requestId));
        String response = mvc.perform(post(path).with(user(actor.email).roles(actor.role.name())).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode fetch(Account actor, String path, int expected) throws Exception {
        return fetch(actor, path, Map.of(), expected);
    }

    private JsonNode fetch(Account actor, String path, Map<String, String> params, int expected) throws Exception {
        MockHttpServletRequestBuilder request = get(path).with(user(actor.email).roles(actor.role.name()));
        params.forEach(request::param);
        String response = mvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private void mark(Account actor, String path, long throughMessageId, int expected) throws Exception {
        mvc.perform(post(path).with(user(actor.email).roles(actor.role.name())).with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("throughMessageId", throughMessageId))))
                .andExpect(status().is(expected));
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("messages").forEach(message -> ids.add(message.get("messageId").asLong()));
        return ids;
    }

    @Test void residentWithoutConversationGetsEmptyPageWithoutCreatingOne() throws Exception {
        JsonNode page = fetch(resident, residentPath(), 200);
        assertThat(page.get("conversationId").isNull()).isTrue();
        assertThat(page.get("messages").isEmpty()).isTrue();
        assertThat(page.get("nextBeforeId").isNull()).isTrue();
        assertThat(page.get("unreadCount").asLong()).isZero();
        assertThat(conversations.findByResident(resident)).isEmpty();
    }

    @Test void residentHistoryShowsOnlyOwnMessagesInOrderWithSenderDetails() throws Exception {
        JsonNode first = send(resident, residentPath(), "Question", "r1");
        long id = first.get("conversationId").asLong();
        JsonNode reply = send(manager, managerMessagesPath(id), "Reply", "m1");
        JsonNode third = send(resident, residentPath(), "Thanks", "r2");
        Account otherResident = account(resident.community, Account.Role.RESIDENT, Account.Status.APPROVED);
        send(otherResident, residentPath(), "Other conversation", "other");

        JsonNode page = fetch(resident, residentPath(), 200);
        assertThat(page.get("conversationId").asLong()).isEqualTo(id);
        assertThat(ids(page)).containsExactly(first.get("messageId").asLong(),
                reply.get("messageId").asLong(), third.get("messageId").asLong());
        assertThat(page.get("messages").get(1).get("senderId").asLong()).isEqualTo(manager.id);
        assertThat(page.get("messages").get(1).get("senderName").asText()).isEqualTo(manager.name);
        assertThat(page.get("messages").get(1).get("senderRole").asText()).isEqualTo("MANAGER");
    }

    @Test void managerListIsCommunityScopedAndOrderedByLatestMessage() throws Exception {
        Account secondResident = account(resident.community, Account.Role.RESIDENT, Account.Status.APPROVED);
        JsonNode secondFirst = send(secondResident, residentPath(), "Second resident first", "b1");
        JsonNode first = send(resident, residentPath(), "First resident", "a1");
        send(manager, managerMessagesPath(secondFirst.get("conversationId").asLong()), "Later reply", "m1");
        Account foreignResident = account(foreignManager.community, Account.Role.RESIDENT, Account.Status.APPROVED);
        send(foreignResident, residentPath(), "Foreign", "f1");

        JsonNode list = fetch(manager, managerListPath(), 200);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("conversationId").asLong()).isEqualTo(secondFirst.get("conversationId").asLong());
        assertThat(list.get(1).get("conversationId").asLong()).isEqualTo(first.get("conversationId").asLong());
        assertThat(list.get(0).get("residentId").asLong()).isEqualTo(secondResident.id);
        assertThat(list.get(0).get("residentRoom").asText()).isEqualTo("101");
        assertThat(list.get(0).get("lastMessageContent").asText()).isEqualTo("Later reply");
        assertThat(list.get(0).get("lastMessageAt").isTextual()).isTrue();
    }

    @Test void messagePagesUseBeforeIdWithoutDuplicatesOrMissingMessages() throws Exception {
        List<Long> sent = new ArrayList<>();
        long conversationId = 0;
        for (int i = 1; i <= 5; i++) {
            JsonNode message = send(resident, residentPath(), "Message " + i, "r" + i);
            conversationId = message.get("conversationId").asLong();
            sent.add(message.get("messageId").asLong());
        }

        JsonNode newest = fetch(manager, managerMessagesPath(conversationId), Map.of("size", "2"), 200);
        assertThat(ids(newest)).containsExactly(sent.get(3), sent.get(4));
        long before = newest.get("nextBeforeId").asLong();
        assertThat(before).isEqualTo(sent.get(3));
        JsonNode middle = fetch(manager, managerMessagesPath(conversationId),
                Map.of("size", "2", "beforeId", Long.toString(before)), 200);
        assertThat(ids(middle)).containsExactly(sent.get(1), sent.get(2));
        JsonNode oldest = fetch(manager, managerMessagesPath(conversationId),
                Map.of("size", "2", "beforeId", middle.get("nextBeforeId").asText()), 200);
        assertThat(ids(oldest)).containsExactly(sent.get(0));
        assertThat(oldest.get("nextBeforeId").isNull()).isTrue();
    }

    @Test void residentUnreadChangesOnlyAfterExplicitReadAndLaterReply() throws Exception {
        JsonNode first = send(resident, residentPath(), "Question", "r1");
        long id = first.get("conversationId").asLong();
        JsonNode reply = send(manager, managerMessagesPath(id), "Answer", "m1");

        assertThat(fetch(resident, residentPath(), 200).get("unreadCount").asLong()).isEqualTo(1);
        assertThat(fetch(resident, residentPath(), 200).get("unreadCount").asLong()).isEqualTo(1);
        mark(resident, "/api/direct-messages/me/read", reply.get("messageId").asLong(), 204);
        assertThat(fetch(resident, residentPath(), 200).get("unreadCount").asLong()).isZero();
        send(manager, managerMessagesPath(id), "Another answer", "m2");
        assertThat(fetch(resident, residentPath(), 200).get("unreadCount").asLong()).isEqualTo(1);
    }

    @Test void managerReadPositionIsSharedAndOnlyResidentMessagesCount() throws Exception {
        JsonNode first = send(resident, residentPath(), "Question", "r1");
        long id = first.get("conversationId").asLong();
        assertThat(fetch(manager, managerMessagesPath(id), 200).get("unreadCount").asLong()).isEqualTo(1);
        mark(manager, managerReadPath(id), first.get("messageId").asLong(), 204);
        assertThat(fetch(managerTwo, managerListPath(), 200).get(0).get("unreadCount").asLong()).isZero();
        send(managerTwo, managerMessagesPath(id), "Reply", "m1");
        assertThat(fetch(manager, managerListPath(), 200).get(0).get("unreadCount").asLong()).isZero();
        send(resident, residentPath(), "Another question", "r2");
        assertThat(fetch(managerTwo, managerListPath(), 200).get(0).get("unreadCount").asLong()).isEqualTo(1);
    }

    @Test void cursorNeverMovesBackwardAndOtherConversationMessageIsRejected() throws Exception {
        JsonNode first = send(resident, residentPath(), "First", "r1");
        JsonNode second = send(resident, residentPath(), "Second", "r2");
        Account otherResident = account(resident.community, Account.Role.RESIDENT, Account.Status.APPROVED);
        JsonNode foreignMessage = send(otherResident, residentPath(), "Other", "o1");
        long id = first.get("conversationId").asLong();

        mark(manager, managerReadPath(id), second.get("messageId").asLong(), 204);
        mark(manager, managerReadPath(id), first.get("messageId").asLong(), 204);
        mark(manager, managerReadPath(id), foreignMessage.get("messageId").asLong(), 404);
        mark(resident, "/api/direct-messages/me/read", foreignMessage.get("messageId").asLong(), 404);
        assertThat(conversations.findByResident(resident).orElseThrow().residentLastReadMessageId).isNull();
        mark(resident, "/api/direct-messages/me/read", second.get("messageId").asLong(), 204);
        mark(resident, "/api/direct-messages/me/read", first.get("messageId").asLong(), 204);
        DirectConversation conversation = conversations.findByResident(resident).orElseThrow();
        assertThat(conversation.managerLastReadMessageId).isEqualTo(second.get("messageId").asLong());
        assertThat(conversation.residentLastReadMessageId).isEqualTo(second.get("messageId").asLong());
    }

    @Test void newReadEndpointsEnforceRoleApprovalAndCommunity() throws Exception {
        JsonNode message = send(resident, residentPath(), "Question", "r1");
        long id = message.get("conversationId").asLong();
        fetch(foreignManager, managerMessagesPath(id), 404);
        mark(foreignManager, managerReadPath(id), message.get("messageId").asLong(), 404);
        fetch(provider, managerListPath(), 403);
        fetch(provider, managerMessagesPath(id), 403);
        mark(provider, managerReadPath(id), message.get("messageId").asLong(), 403);
        fetch(pending, residentPath(), 403);
        fetch(rejected, residentPath(), 403);
    }
}
