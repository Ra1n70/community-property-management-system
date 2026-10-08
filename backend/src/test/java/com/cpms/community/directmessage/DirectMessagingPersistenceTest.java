package com.cpms.community.directmessage;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:directmessaging;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "locker.pickup-code-secret=test-only", "demo.manager-password=", "demo.invite-code=test"
})
@ActiveProfiles("demo")
class DirectMessagingPersistenceTest {
    @Autowired AccountRepository accounts;
    @Autowired DirectConversationRepository conversations;
    @Autowired DirectMessageRepository messages;

    private Account resident(String community) {
        Account account = new Account();
        account.email = UUID.randomUUID() + "@test.local";
        account.passwordHash = "test";
        account.name = "Resident";
        account.community = community;
        account.role = Account.Role.RESIDENT;
        account.status = Account.Status.APPROVED;
        return accounts.saveAndFlush(account);
    }

    private DirectConversation conversation(Account resident) {
        DirectConversation conversation = new DirectConversation();
        conversation.resident = resident;
        conversation.community = resident.community;
        return conversations.saveAndFlush(conversation);
    }

    private DirectMessage message(DirectConversation conversation, Account sender, String requestId,
                                  String content, Instant createdAt) {
        DirectMessage message = new DirectMessage();
        message.conversation = conversation;
        message.sender = sender;
        message.clientRequestId = requestId;
        message.content = content;
        message.createdAt = createdAt;
        return messages.saveAndFlush(message);
    }

    @Test void oneConversationPerResident() {
        Account first = resident("Community " + UUID.randomUUID());
        Account second = resident(first.community);
        DirectConversation original = conversation(first);
        DirectConversation another = conversation(second);

        assertThat(conversations.findByResident(first).map(c -> c.id)).contains(original.id);
        assertThat(conversations.findByIdAndCommunity(original.id, first.community).map(c -> c.id)).contains(original.id);
        assertThat(conversations.findByIdAndCommunity(original.id, "Another community")).isEmpty();
        assertThat(conversations.findByCommunityOrderByCreatedAtDescIdDesc(first.community))
                .extracting(c -> c.id).containsExactlyInAnyOrder(original.id, another.id);
        assertThatThrownBy(() -> conversation(first)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void sameSendRequestCannotCreateTwoMessages() {
        Account sender = resident("Community " + UUID.randomUUID());
        DirectConversation conversation = conversation(sender);
        DirectMessage original = message(conversation, sender, "request-1", "Hello", Instant.now());

        assertThat(messages.findByConversationAndSenderAndClientRequestId(conversation, sender, "request-1"))
                .map(m -> m.id).contains(original.id);
        assertThatThrownBy(() -> message(conversation, sender, "request-1", "Hello", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(messages.findByConversationOrderByCreatedAtAscIdAsc(conversation))
                .extracting(m -> m.id).containsExactly(original.id);
    }

    @Test void messagesAreOrderedByTimeThenIdWithinConversation() {
        Account sender = resident("Community " + UUID.randomUUID());
        DirectConversation conversation = conversation(sender);
        DirectConversation other = conversation(resident(sender.community));
        Instant time = Instant.parse("2026-01-01T12:00:00Z");
        DirectMessage firstTie = message(conversation, sender, "request-1", "First tie", time);
        DirectMessage secondTie = message(conversation, sender, "request-2", "Second tie", time);
        DirectMessage earlier = message(conversation, sender, "request-3", "Earlier", time.minusSeconds(1));
        message(other, other.resident, "request-4", "Other conversation", time);

        assertThat(messages.findByConversationOrderByCreatedAtAscIdAsc(conversation))
                .extracting(m -> m.id).containsExactly(earlier.id, firstTie.id, secondTie.id);
    }

    @Test void contentColumnHasOneThousandCharacterLimit() {
        Account sender = resident("Community " + UUID.randomUUID());
        DirectConversation conversation = conversation(sender);
        message(conversation, sender, "request-1", "x".repeat(1000), Instant.now());

        assertThatThrownBy(() -> message(conversation, sender, "request-2", "x".repeat(1001), Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
