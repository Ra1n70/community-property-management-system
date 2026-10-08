package com.cpms.community.residentchat;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class ResidentChatService {
    private final AccountService accounts;
    private final AccountRepository accountRows;
    private final ResidentConversationRepository conversations;
    private final ResidentChatMessageRepository messages;

    public ResidentChatService(AccountService accounts, AccountRepository accountRows,
                               ResidentConversationRepository conversations, ResidentChatMessageRepository messages) {
        this.accounts = accounts;
        this.accountRows = accountRows;
        this.conversations = conversations;
        this.messages = messages;
    }

    public ResidentChatApi.ConversationView create(String email, Long targetId) {
        Account current = resident(email);
        if (current.id.equals(targetId))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Cannot start a conversation with yourself.");

        long oneId = Math.min(current.id, targetId);
        long twoId = Math.max(current.id, targetId);
        // Every request for this pair locks the same account row before looking up the conversation.
        Account one = accountRows.lockById(oneId).orElseThrow(this::missingResident);
        Account two = accountRows.findById(twoId).orElseThrow(this::missingResident);
        if (!eligible(one) || !eligible(two) || !one.community.equals(two.community)
                || !current.community.equals(one.community)) throw missingResident();

        ResidentConversation conversation = conversations.findByResidentOneAndResidentTwo(one, two).orElseGet(() -> {
            ResidentConversation created = new ResidentConversation();
            created.residentOne = one;
            created.residentTwo = two;
            created.community = one.community;
            return conversations.saveAndFlush(created);
        });
        Account peer = other(conversation, current);
        return new ResidentChatApi.ConversationView(conversation.id, peer.id, peer.name);
    }

    @Transactional(readOnly = true)
    public List<ResidentChatApi.ConversationSummary> list(String email) {
        Account current = resident(email);
        List<ResidentChatApi.ConversationSummary> result = new ArrayList<>();
        for (ResidentConversation conversation : conversations.findByResidentOneOrResidentTwo(current, current)) {
            if (!conversation.community.equals(current.community)) continue;
            Account peer = other(conversation, current);
            ResidentChatMessage latest = messages.findFirstByConversationOrderByCreatedAtDescIdDesc(conversation).orElse(null);
            result.add(new ResidentChatApi.ConversationSummary(conversation.id, peer.id, peer.name,
                    latest == null ? null : latest.content, latest == null ? null : latest.createdAt,
                    unread(conversation, current, peer)));
        }
        result.sort(Comparator.comparing(ResidentChatApi.ConversationSummary::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ResidentChatApi.ConversationSummary::conversationId, Comparator.reverseOrder()));
        return result;
    }

    @Transactional(readOnly = true)
    public ResidentChatApi.MessagePage history(String email, Long conversationId, Long beforeId, int size) {
        Account current = resident(email);
        ResidentConversation conversation = conversation(current, conversationId);
        List<ResidentChatMessage> fetched = beforeId == null
                ? messages.findByConversationOrderByIdDesc(conversation, PageRequest.of(0, size + 1))
                : messages.findByConversationAndIdLessThanOrderByIdDesc(conversation, beforeId, PageRequest.of(0, size + 1));
        boolean more = fetched.size() > size;
        List<ResidentChatMessage> shown = new ArrayList<>(fetched.subList(0, Math.min(size, fetched.size())));
        Long nextBeforeId = more ? shown.get(shown.size() - 1).id : null;
        Collections.reverse(shown);
        return new ResidentChatApi.MessagePage(conversation.id,
                shown.stream().map(ResidentChatApi.MessageView::of).toList(), nextBeforeId,
                unread(conversation, current, other(conversation, current)));
    }

    public ResidentChatApi.MessageView send(String email, Long conversationId, ResidentChatApi.Send request) {
        Account current = resident(email);
        ResidentConversation conversation = conversation(current, conversationId);
        ResidentChatMessage existing = messages.findByConversationAndSenderAndClientRequestId(
                conversation, current, request.clientRequestId()).orElse(null);
        if (existing != null) {
            if (!existing.content.equals(request.content()))
                throw AccountService.fail(HttpStatus.CONFLICT, "This request ID was already used for different content.");
            return ResidentChatApi.MessageView.of(existing);
        }
        ResidentChatMessage message = new ResidentChatMessage();
        message.conversation = conversation;
        message.sender = current;
        message.content = request.content();
        message.clientRequestId = request.clientRequestId();
        return ResidentChatApi.MessageView.of(messages.saveAndFlush(message));
    }

    public void read(String email, Long conversationId, Long throughMessageId) {
        Account current = resident(email);
        ResidentConversation conversation = conversation(current, conversationId);
        messages.findByIdAndConversation(throughMessageId, conversation)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Message not found."));
        if (current.id.equals(accountId(conversation.residentOne))) {
            if (conversation.residentOneLastReadMessageId == null || throughMessageId > conversation.residentOneLastReadMessageId)
                conversation.residentOneLastReadMessageId = throughMessageId;
        } else if (conversation.residentTwoLastReadMessageId == null || throughMessageId > conversation.residentTwoLastReadMessageId) {
            conversation.residentTwoLastReadMessageId = throughMessageId;
        }
    }

    private Account resident(String email) {
        Account account = accounts.current(email);
        if (!eligible(account)) throw AccountService.fail(HttpStatus.FORBIDDEN, "Approved resident access required.");
        return account;
    }

    private boolean eligible(Account account) {
        return account.role == Account.Role.RESIDENT && account.status == Account.Status.APPROVED;
    }

    private ResidentConversation conversation(Account current, Long id) {
        ResidentConversation conversation = conversations.findByIdAndCommunity(id, current.community)
                .orElseThrow(this::missingConversation);
        if (!current.id.equals(accountId(conversation.residentOne)) && !current.id.equals(accountId(conversation.residentTwo)))
            throw missingConversation();
        return conversation;
    }

    private Account other(ResidentConversation conversation, Account current) {
        return org.hibernate.Hibernate.unproxy(
                current.id.equals(accountId(conversation.residentOne)) ? conversation.residentTwo : conversation.residentOne,
                Account.class);
    }

    private long unread(ResidentConversation conversation, Account current, Account peer) {
        Long cursor = current.id.equals(accountId(conversation.residentOne))
                ? conversation.residentOneLastReadMessageId : conversation.residentTwoLastReadMessageId;
        return cursor == null ? messages.countByConversationAndSender(conversation, peer)
                : messages.countByConversationAndSenderAndIdGreaterThan(conversation, peer, cursor);
    }

    private RuntimeException missingResident() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Resident not found.");
    }

    private Long accountId(Account account) {
        return org.hibernate.Hibernate.unproxy(account, Account.class).id;
    }

    private RuntimeException missingConversation() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Conversation not found.");
    }
}
