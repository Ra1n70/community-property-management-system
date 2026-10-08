package com.cpms.community.directmessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:directmessagingupgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "messages.upload-dir=${java.io.tmpdir}/cpms-test-messages"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DirectMessagingUpgradeTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired DirectConversationRepository conversations;
    @Autowired DirectMessageRepository messages;
    @Autowired DirectMessagingService service;
    @Autowired DirectMessageEvents events;
    @Autowired EntityManagerFactory entities;

    String community;
    Account manager, foreignManager, provider;

    @BeforeEach void setup() {
        community = "Community " + UUID.randomUUID();
        manager = account(community, Account.Role.MANAGER, "Morgan Lee");
        foreignManager = account("Community " + UUID.randomUUID(), Account.Role.MANAGER, "Riley Chen");
        provider = account(community, Account.Role.PROVIDER, "City Plumbing");
    }

    Account account(String community, Account.Role role, String name) {
        Account a = new Account();
        a.email = UUID.randomUUID() + "@example.com";
        a.passwordHash = "x";
        a.name = name;
        a.community = community;
        a.room = role == Account.Role.RESIDENT ? "" + (100 + (int) (Math.random() * 800)) : null;
        a.role = role;
        a.status = Account.Status.APPROVED;
        return accounts.saveAndFlush(a);
    }

    JsonNode call(Account a, MockHttpServletRequestBuilder req, Object body, int expected) throws Exception {
        req.with(user(a.email).roles(a.role.name())).with(csrf());
        if (body != null) req.contentType("application/json").content(json.writeValueAsString(body));
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isBlank() ? json.nullNode() : json.readTree(response);
    }

    JsonNode residentSays(Account resident, String text) throws Exception {
        return call(resident, post("/api/direct-messages/me/messages"), Map.of("content", text, "clientRequestId", UUID.randomUUID().toString()), 200);
    }

    long statements(Runnable work) {
        var stats = entities.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        work.run();
        return stats.getPrepareStatementCount();
    }

    @Test void inboxUsesTheSameNumberOfQueriesForAnyNumberOfConversationsAndCountsUnread() throws Exception {
        Account first = account(community, Account.Role.RESIDENT, "Avery Park");
        residentSays(first, "Is the gym open on Sunday?");
        residentSays(first, "Also, where do I park guests?");
        long few = statements(() -> service.managerConversations(manager.email, "", false, 100));
        for (int i = 0; i < 12; i++) residentSays(account(community, Account.Role.RESIDENT, "Resident " + i), "Hello from unit " + i);
        long many = statements(() -> service.managerConversations(manager.email, "", false, 100));
        assertThat(many).isEqualTo(few);

        JsonNode inbox = call(manager, get("/api/direct-messages/conversations"), null, 200);
        assertThat(inbox).hasSize(13);
        JsonNode avery = call(manager, get("/api/direct-messages/conversations").param("q", "avery"), null, 200);
        assertThat(avery).hasSize(1);
        assertThat(avery.get(0).get("lastMessageContent").asText()).isEqualTo("Also, where do I park guests?");
        assertThat(avery.get(0).get("unreadCount").asLong()).isEqualTo(2);
        assertThat(call(manager, get("/api/direct-messages/conversations").param("q", "park guests"), null, 200)).hasSize(1);
        assertThat(call(manager, get("/api/direct-messages/conversations").param("q", "100%"), null, 200)).isEmpty();
        assertThat(call(manager, get("/api/direct-messages/unread"), null, 200).get("unread").asLong()).isEqualTo(14);

        long conversationId = avery.get(0).get("conversationId").asLong();
        long latest = messages.findFirstByConversationOrderByCreatedAtDescIdDesc(conversations.findById(conversationId).orElseThrow()).orElseThrow().id;
        call(manager, post("/api/direct-messages/conversations/" + conversationId + "/read"), Map.of("throughMessageId", latest), 204);
        assertThat(call(manager, get("/api/direct-messages/unread"), null, 200).get("unread").asLong()).isEqualTo(12);
        assertThat(call(manager, get("/api/direct-messages/conversations").param("unreadOnly", "true"), null, 200)).hasSize(12);

        call(manager, post("/api/direct-messages/conversations/" + conversationId + "/messages"), Map.of("content", "Sunday 8–6.", "clientRequestId", "m-1"), 200);
        assertThat(call(first, get("/api/direct-messages/unread"), null, 200).get("unread").asLong()).isEqualTo(1);
        assertThat(call(foreignManager, get("/api/direct-messages/unread"), null, 200).get("unread").asLong()).isZero();
        call(provider, get("/api/direct-messages/unread"), null, 403);
    }

    static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test void photosAndPdfsAreStoredPrivatelyAndShownWithTheMessage() throws Exception {
        Account resident = account(community, Account.Role.RESIDENT, "Jamie Fox");
        Account neighbor = account(community, Account.Role.RESIDENT, "Sam Rivera");
        var request = new MockMultipartFile("request", "", "application/json",
                json.writeValueAsBytes(Map.of("content", "", "clientRequestId", "with-files")));
        var photo = new MockMultipartFile("files", "leak.jpg", "image/jpeg", png());
        var pdf = new MockMultipartFile("files", "lease note.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        String body = mvc.perform(multipart("/api/direct-messages/me/messages").file(request).file(photo).file(pdf)
                        .with(user(resident.email).roles("RESIDENT")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode sent = json.readTree(body);
        assertThat(sent.get("attachments")).hasSize(2);
        assertThat(sent.get("attachments").get(0).get("filename").asText()).isEqualTo("leak.png");
        assertThat(sent.get("attachments").get(0).get("image").asBoolean()).isTrue();
        assertThat(sent.get("attachments").get(1).get("filename").asText()).isEqualTo("lease note.pdf");
        long photoId = sent.get("attachments").get(0).get("id").asLong(), pdfId = sent.get("attachments").get(1).get("id").asLong();

        MvcResult image = mvc.perform(get("/api/direct-messages/attachments/" + photoId).with(user(resident.email).roles("RESIDENT")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn();
        assertThat(image.getResponse().getHeader("Content-Disposition")).startsWith("inline");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(image.getResponse().getContentAsByteArray()))).isNotNull();
        mvc.perform(get("/api/direct-messages/attachments/" + pdfId).with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        call(neighbor, get("/api/direct-messages/attachments/" + photoId), null, 404);
        call(foreignManager, get("/api/direct-messages/attachments/" + photoId), null, 404);
        call(provider, get("/api/direct-messages/attachments/" + photoId), null, 403);

        JsonNode page = call(resident, get("/api/direct-messages/me/messages"), null, 200);
        assertThat(page.get("messages").get(0).get("attachments")).hasSize(2);
        JsonNode inbox = call(manager, get("/api/direct-messages/conversations").param("q", "jamie"), null, 200);
        assertThat(inbox.get(0).get("lastMessageContent").asText()).isEqualTo("📎 2 attachments");

        var text = new MockMultipartFile("files", "notes.txt", "text/plain", "hello".getBytes());
        var again = new MockMultipartFile("request", "", "application/json", json.writeValueAsBytes(Map.of("content", "See file", "clientRequestId", "bad-file")));
        mvc.perform(multipart("/api/direct-messages/me/messages").file(again).file(text).with(user(resident.email).roles("RESIDENT")).with(csrf()))
                .andExpect(status().isBadRequest());
        var many = multipart("/api/direct-messages/me/messages").file(new MockMultipartFile("request", "", "application/json",
                json.writeValueAsBytes(Map.of("content", "Four", "clientRequestId", "four"))));
        for (int i = 0; i < 4; i++) many.file(new MockMultipartFile("files", "p" + i + ".jpg", "image/jpeg", png()));
        mvc.perform(many.with(user(resident.email).roles("RESIDENT")).with(csrf())).andExpect(status().isBadRequest());
        var empty = new MockMultipartFile("request", "", "application/json", json.writeValueAsBytes(Map.of("content", " ", "clientRequestId", "empty")));
        mvc.perform(multipart("/api/direct-messages/me/messages").file(empty).with(user(resident.email).roles("RESIDENT")).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test void openStreamsReceiveSignalsOnlyForTheirOwnConversations() throws Exception {
        Account resident = account(community, Account.Role.RESIDENT, "Drew Kim");
        Account neighbor = account(community, Account.Role.RESIDENT, "Lee Wong");
        MvcResult managerStream = mvc.perform(get("/api/direct-messages/stream").with(user(manager.email).roles("MANAGER")))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult neighborStream = mvc.perform(get("/api/direct-messages/stream").with(user(neighbor.email).roles("RESIDENT")))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult foreignStream = mvc.perform(get("/api/direct-messages/stream").with(user(foreignManager.email).roles("MANAGER")))
                .andExpect(request().asyncStarted()).andReturn();
        call(provider, get("/api/direct-messages/stream"), null, 403);

        JsonNode sent = residentSays(resident, "The hallway light is out.");
        String expected = "\"type\":\"message\",\"conversationId\":" + sent.get("conversationId").asLong() + ",\"messageId\":" + sent.get("messageId").asLong();
        assertThat(managerStream.getResponse().getContentAsString()).contains("event:ready").contains("event:dm").contains(expected)
                .doesNotContain("hallway");
        assertThat(neighborStream.getResponse().getContentAsString()).doesNotContain("event:dm");
        assertThat(foreignStream.getResponse().getContentAsString()).doesNotContain("event:dm");
    }

    @Test void olderConversationsGetTheirInboxSummaryOnStartup() {
        Account resident = account(community, Account.Role.RESIDENT, "Casey Moore");
        DirectConversation conversation = new DirectConversation();
        conversation.resident = resident;
        conversation.community = community;
        conversations.saveAndFlush(conversation);
        DirectMessage message = new DirectMessage();
        message.conversation = conversation;
        message.sender = resident;
        message.clientRequestId = "legacy";
        message.content = "Written before the summary columns existed";
        messages.saveAndFlush(message);
        service.backfillSummaries();
        DirectConversation filled = conversations.findById(conversation.id).orElseThrow();
        assertThat(filled.lastMessageId).isEqualTo(message.id);
        assertThat(filled.lastMessagePreview).isEqualTo("Written before the summary columns existed");
    }
}
