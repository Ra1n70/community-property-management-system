package com.cpms.community.discussion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpms.community.*;
import com.cpms.community.discussion.repository.CommentLikeRepository;
import com.cpms.community.discussion.repository.CommentRepository;
import com.cpms.community.discussion.repository.DiscussionRepository;
import com.cpms.community.discussion.repository.DiscussionLikeRepository;
import com.cpms.community.discussion.repository.ReportRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "locker.pickup-code-secret=integration-test-only-not-for-deployment",
        "spring.datasource.url=jdbc:h2:mem:DiscussionBoardFlowTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "demo.invite-code=test-invite",
        "demo.manager-password=TestManager123!"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DiscussionBoardFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired DiscussionRepository discussions;
    @Autowired CommentRepository comments;
    @Autowired DiscussionLikeRepository discussionLikes;
    @Autowired CommentLikeRepository commentLikes;
    @Autowired ReportRepository reports;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void reset() {
        reports.deleteAll();
        commentLikes.deleteAll();
        discussionLikes.deleteAll();
        comments.deleteAll();
        discussions.deleteAll();
        accounts.deleteAll();
        account("resident@test.local", Account.Role.RESIDENT, Account.Status.APPROVED, "Demo Community");
        account("manager@test.local", Account.Role.MANAGER, Account.Status.APPROVED, "Demo Community");
        account("pending@test.local", Account.Role.RESIDENT, Account.Status.PENDING, "Demo Community");
        account("other@test.local", Account.Role.RESIDENT, Account.Status.APPROVED, "Other Community");
    }

    @Test
    void discussionBoardP0Flow() throws Exception {
        mvc.perform(post("/api/discussions").with(user("pending@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discussionJson("Pending post", "Should be blocked", "FEEDBACK")))
                .andExpect(status().isForbidden());

        MvcResult otherResult = mvc.perform(post("/api/discussions").with(user("other@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discussionJson("Other community post", "Other", "LIFE")))
                .andExpect(status().isCreated())
                .andReturn();
        Long otherDiscussionId = readId(otherResult);

        mvc.perform(get("/api/discussions/" + otherDiscussionId).with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isNotFound());

        MvcResult postResult = mvc.perform(post("/api/discussions").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discussionJson("Garage light", "The light is out.", "FEEDBACK")))
                .andExpect(status().isCreated())
                .andReturn();
        Long discussionId = readId(postResult);

        mvc.perform(get("/api/discussions").with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + discussionId + ")]").exists());

        MvcResult commentResult = mvc.perform(post("/api/discussions/" + discussionId + "/comments")
                        .with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"I noticed it too.\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long commentId = readId(commentResult);

        mvc.perform(post("/api/discussions/" + discussionId + "/like")
                        .with(user("resident@test.local").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(true));
        mvc.perform(post("/api/discussions/" + discussionId + "/like")
                        .with(user("resident@test.local").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false));

        mvc.perform(post("/api/comments/" + commentId + "/like")
                        .with(user("resident@test.local").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(true));

        mvc.perform(post("/api/reports").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("DISCUSSION", discussionId, "FALSE_INFO")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/reports").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("DISCUSSION", discussionId, "FALSE_INFO")))
                .andExpect(status().isConflict());

        MvcResult reportsResult = mvc.perform(get("/api/manager/reports?status=PENDING")
                        .with(user("manager@test.local").roles("MANAGER")))
                .andExpect(status().isOk())
                .andReturn();
        Long reportId = JSON.readTree(reportsResult.getResponse().getContentAsString()).get(0).get("id").asLong();

        mvc.perform(post("/api/manager/reports/" + reportId + "/handle")
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"IGNORE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        mvc.perform(delete("/api/manager/discussions/" + discussionId)
                        .with(user("manager@test.local").roles("MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Violates policy.\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + discussionId).with(user("resident@test.local").roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        assertThat(discussions.findById(discussionId)).isPresent();
        assertThat(discussions.findById(discussionId).orElseThrow().deleteReason).isEqualTo("Violates policy.");
    }


    @Test void ownershipFieldsAndModerationMatchTheFrontend() throws Exception {
        long id=readId(mvc.perform(post("/api/discussions").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(discussionJson("Community picnic","Saturday afternoon","ACTIVITY")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.owned").value(true)).andReturn());
        mvc.perform(get("/api/manager/discussions/"+id).with(user("manager@test.local").roles("MANAGER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.owned").value(false));
        mvc.perform(put("/api/discussions/"+id).with(user("manager@test.local").roles("MANAGER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(discussionJson("Changed","Not the author","ACTIVITY")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/manager/discussions/"+id+"/pin").with(user("manager@test.local").roles("MANAGER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
            .andExpect(status().isNoContent());
        long commentId=readId(mvc.perform(post("/api/discussions/"+id+"/comments").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Count me in.\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.owned").value(true)).andReturn());
        mvc.perform(get("/api/manager/discussions/"+id).with(user("manager@test.local").roles("MANAGER")))
            .andExpect(jsonPath("$.pinned").value(true)).andExpect(jsonPath("$.comments[0].owned").value(false));
        mvc.perform(delete("/api/manager/comments/"+commentId).with(user("manager@test.local").roles("MANAGER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Moderation test\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/"+id).with(user("resident@test.local").roles("RESIDENT")))
            .andExpect(jsonPath("$.comments[0].deleted").value(true)).andExpect(jsonPath("$.comments[0].owned").value(false))
            .andExpect(jsonPath("$.comments[0].content").value("This content has been deleted."));
        mvc.perform(delete("/api/manager/discussions/"+id).with(user("manager@test.local").roles("MANAGER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Moderation test\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/manager/discussions").with(user("manager@test.local").roles("MANAGER")))
            .andExpect(jsonPath("$[0].deleted").value(true));
        mvc.perform(get("/api/discussions/"+id).with(user("resident@test.local").roles("RESIDENT")))
            .andExpect(jsonPath("$.owned").value(false)).andExpect(jsonPath("$.comments").isEmpty());
    }

    /** Residents need the account of a resident author to start a resident chat from a post or comment. */
    @Test void residentAuthorsCarryTheirAccountIdForResidentChat() throws Exception {
        Account author = accounts.findByEmail("resident@test.local").orElseThrow();
        account("neighbor@test.local", Account.Role.RESIDENT, Account.Status.APPROVED, "Demo Community");
        long id = readId(mvc.perform(post("/api/discussions").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(discussionJson("Bike for sale", "Barely used", "TRADING")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/discussions/" + id + "/comments").with(user("manager@test.local").roles("MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Please keep trades in the lobby.\"}"))
                .andExpect(status().isCreated());
        long residentComment = readId(mvc.perform(post("/api/discussions/" + id + "/comments").with(user("resident@test.local").roles("RESIDENT")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Still available.\"}"))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(get("/api/discussions/" + id).with(user("neighbor@test.local").roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorRole").value("RESIDENT"))
                .andExpect(jsonPath("$.authorAccountId").value(author.id))
                .andExpect(jsonPath("$.comments[0].authorRole").value("MANAGER"))
                .andExpect(jsonPath("$.comments[0].authorAccountId").doesNotExist())
                .andExpect(jsonPath("$.comments[1].authorAccountId").value(author.id))
                .andExpect(jsonPath("$.comments[1].owned").value(false));

        // A deleted comment no longer points at its author.
        mvc.perform(delete("/api/manager/comments/" + residentComment).with(user("manager@test.local").roles("MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Off topic\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + id).with(user("neighbor@test.local").roles("RESIDENT")))
                .andExpect(jsonPath("$.comments[1].deleted").value(true))
                .andExpect(jsonPath("$.comments[1].authorAccountId").doesNotExist());
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

    private String discussionJson(String title, String content, String category) {
        return "{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"category\":\"" + category + "\"}";
    }

    private String reportJson(String targetType, Long targetId, String reason) {
        return "{\"targetType\":\"" + targetType + "\",\"targetId\":" + targetId + ",\"reason\":\"" + reason + "\"}";
    }

    private Long readId(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }
}
