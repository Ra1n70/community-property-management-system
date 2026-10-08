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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:directmessagingflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DirectMessagingFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountRepository accounts;
    @Autowired DirectConversationRepository conversations;
    @Autowired DirectMessageRepository messages;

    Account resident, manager, foreignManager, provider, pending, rejected;

    @BeforeEach void setup() {
        String community = "Community " + UUID.randomUUID();
        resident = account(community, Account.Role.RESIDENT, Account.Status.APPROVED);
        manager = account(community, Account.Role.MANAGER, Account.Status.APPROVED);
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
        account.community = community;
        account.role = role;
        account.status = status;
        return accounts.saveAndFlush(account);
    }

    private JsonNode send(Account actor, String path, String content, String requestId, int expected) throws Exception {
        String response = mvc.perform(post(path).with(user(actor.email).roles(actor.role.name())).with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("content", content, "clientRequestId", requestId))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private String residentPath() { return "/api/direct-messages/me/messages"; }
    private String managerPath(Long id) { return "/api/direct-messages/conversations/" + id + "/messages"; }

    @Test void residentCreatesConversationOnFirstSendAndReusesItOnSecond() throws Exception {
        assertThat(conversations.findByResident(resident)).isEmpty();
        JsonNode first = send(resident, residentPath(), "Parking question", "request-1", 200);
        DirectConversation conversation = conversations.findByResident(resident).orElseThrow();

        assertThat(conversation.community).isEqualTo(resident.community);
        assertThat(first.get("conversationId").asLong()).isEqualTo(conversation.id);
        assertThat(first.get("senderId").asLong()).isEqualTo(resident.id);
        assertThat(first.get("senderName").asText()).isEqualTo(resident.name);
        assertThat(first.get("senderRole").asText()).isEqualTo("RESIDENT");
        assertThat(first.get("content").asText()).isEqualTo("Parking question");
        assertThat(first.get("createdAt").isTextual()).isTrue();
        assertThat(messages.findByConversationAndSenderAndClientRequestId(conversation, resident, "request-1"))
                .map(m -> m.id).contains(first.get("messageId").asLong());

        JsonNode second = send(resident, residentPath(), "Another question", "request-2", 200);
        assertThat(second.get("conversationId").asLong()).isEqualTo(conversation.id);
        assertThat(second.get("messageId").asLong()).isNotEqualTo(first.get("messageId").asLong());
        assertThat(conversations.findByCommunityOrderByCreatedAtDescIdDesc(resident.community)).hasSize(1);
        assertThat(messages.findByConversationOrderByCreatedAtAscIdAsc(conversation)).hasSize(2);
    }

    @Test void sameRequestReturnsOriginalMessageAndChangedContentConflicts() throws Exception {
        JsonNode first = send(resident, residentPath(), "Hello", "request-A", 200);
        JsonNode retry = send(resident, residentPath(), "Hello", "request-A", 200);
        JsonNode conflict = send(resident, residentPath(), "Different", "request-A", 409);
        DirectConversation conversation = conversations.findByResident(resident).orElseThrow();

        assertThat(retry.get("messageId").asLong()).isEqualTo(first.get("messageId").asLong());
        assertThat(conflict.get("message").asText()).contains("request ID");
        assertThat(messages.findByConversationOrderByCreatedAtAscIdAsc(conversation))
                .singleElement().satisfies(message -> {
                    assertThat(message.id).isEqualTo(first.get("messageId").asLong());
                    assertThat(message.content).isEqualTo("Hello");
                });
    }

    @Test void pendingRejectedAndProviderAccountsCannotSend() throws Exception {
        send(pending, residentPath(), "Hello", "request-1", 403);
        send(rejected, residentPath(), "Hello", "request-1", 403);
        send(provider, residentPath(), "Hello", "request-1", 403);
        JsonNode first = send(resident, residentPath(), "Hello", "request-1", 200);
        send(provider, managerPath(first.get("conversationId").asLong()), "Reply", "request-2", 403);

        assertThat(conversations.findByResident(pending)).isEmpty();
        assertThat(conversations.findByResident(rejected)).isEmpty();
        assertThat(conversations.findByResident(provider)).isEmpty();
        assertThat(messages.findByConversationOrderByCreatedAtAscIdAsc(
                conversations.findByResident(resident).orElseThrow())).hasSize(1);
    }

    @Test void managerCanStartConversationWithApprovedResidentOnly() throws Exception {
        String toResident = "/api/direct-messages/residents/" + resident.id + "/messages";
        send(foreignManager, toResident, "Other community", "request-1", 404);
        send(resident, toResident, "Not a manager", "request-1", 403);
        send(manager, "/api/direct-messages/residents/" + pending.id + "/messages", "Pending", "request-1", 404);
        send(manager, "/api/direct-messages/residents/" + provider.id + "/messages", "Provider", "request-1", 404);
        assertThat(conversations.findByResident(resident)).isEmpty();

        JsonNode first = send(manager, toResident, "About your discussion post", "request-1", 200);
        DirectConversation conversation = conversations.findByResident(resident).orElseThrow();
        assertThat(first.get("conversationId").asLong()).isEqualTo(conversation.id);
        assertThat(first.get("senderRole").asText()).isEqualTo("MANAGER");
        JsonNode reply = send(resident, residentPath(), "Thanks", "request-2", 200);
        assertThat(reply.get("conversationId").asLong()).isEqualTo(conversation.id);
        send(manager, toResident, "Follow-up", "request-3", 200);
        assertThat(conversations.findByCommunityOrderByCreatedAtDescIdDesc(resident.community)).hasSize(1);
    }

    @Test void managerCanReplyWithinCommunityButForeignManagerCannot() throws Exception {
        JsonNode first = send(resident, residentPath(), "Question", "request-1", 200);
        Long conversationId = first.get("conversationId").asLong();
        send(foreignManager, managerPath(conversationId), "Other community", "request-2", 404);
        JsonNode reply = send(manager, managerPath(conversationId), "Office reply", "request-2", 200);
        DirectConversation conversation = conversations.findByResident(resident).orElseThrow();

        assertThat(reply.get("conversationId").asLong()).isEqualTo(conversationId);
        assertThat(reply.get("senderId").asLong()).isEqualTo(manager.id);
        assertThat(reply.get("senderName").asText()).isEqualTo(manager.name);
        assertThat(reply.get("senderRole").asText()).isEqualTo("MANAGER");
        assertThat(messages.findByConversationAndSenderAndClientRequestId(conversation, manager, "request-2"))
                .map(m -> m.id).contains(reply.get("messageId").asLong());
        assertThat(messages.findByConversationOrderByCreatedAtAscIdAsc(conversation)).hasSize(2);
    }

    @Test void invalidRequestsDoNotCreateConversation() throws Exception {
        send(resident, residentPath(), "   ", "request-1", 400);
        send(resident, residentPath(), "x".repeat(1001), "request-2", 400);
        send(resident, residentPath(), "Hello", "   ", 400);
        assertThat(conversations.findByResident(resident)).isEmpty();
    }

    @Test void statusIsReadAgainFromDatabaseForAuthenticatedSession() throws Exception {
        resident.passwordHash = passwords.encode("TestPassword123!");
        resident = accounts.saveAndFlush(resident);
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", resident.email).param("password", "TestPassword123!"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);

        mvc.perform(post(residentPath()).session(session).with(csrf()).contentType("application/json")
                        .content("{\"content\":\"Hello\",\"clientRequestId\":\"request-1\"}"))
                .andExpect(status().isOk());
        Account current = accounts.findById(resident.id).orElseThrow();
        current.status = Account.Status.PENDING;
        accounts.saveAndFlush(current);
        mvc.perform(post(residentPath()).session(session).with(csrf()).contentType("application/json")
                        .content("{\"content\":\"Again\",\"clientRequestId\":\"request-2\"}"))
                .andExpect(status().isForbidden());
    }
}
