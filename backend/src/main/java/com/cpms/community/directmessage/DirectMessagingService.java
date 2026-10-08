package com.cpms.community.directmessage;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class DirectMessagingService {
    /** A message to send: text (may be empty when files are attached), the client's retry id and optional files. */
    public record Outgoing(String content, String clientRequestId, List<MultipartFile> files) {
        static Outgoing of(DirectMessagingApi.Send request) { return new Outgoing(request.content(), request.clientRequestId(), List.of()); }
    }
    public record AttachmentFile(String filename, String contentType, byte[] bytes) {}

    private final AccountService accounts;
    private final DirectConversationRepository conversations;
    private final DirectMessageRepository messages;
    private final AccountRepository accountRows;
    private final DirectAttachmentRepository attachments;
    private final DirectAttachmentFiles files;
    private final DirectMessageEvents events;

    public DirectMessagingService(AccountService accounts, DirectConversationRepository conversations,
                                  DirectMessageRepository messages, AccountRepository accountRows,
                                  DirectAttachmentRepository attachments, DirectAttachmentFiles files,
                                  DirectMessageEvents events) {
        this.accounts = accounts;
        this.accountRows = accountRows;
        this.conversations = conversations;
        this.messages = messages;
        this.attachments = attachments;
        this.files = files;
        this.events = events;
    }

    public DirectMessagingApi.MessageView sendAsResident(String email, Outgoing request) {
        Account resident = require(email, Account.Role.RESIDENT);
        DirectConversation conversation = conversations.findByResident(resident).orElseGet(() -> create(resident));
        if (!resident.community.equals(conversation.community)) throw missing();
        return send(conversation, resident, resident.id, request);
    }

    /** Lets a manager open the conversation with a resident (e.g. a post author) even if the resident never wrote first. */
    public DirectMessagingApi.MessageView sendAsManagerToResident(String email, Long residentId, Outgoing request) {
        Account manager = require(email, Account.Role.MANAGER);
        Account resident = accountRows.lockById(residentId).orElseThrow(this::missing);
        if (resident.role != Account.Role.RESIDENT || resident.status != Account.Status.APPROVED
                || !manager.community.equals(resident.community)) throw missing();
        DirectConversation conversation = conversations.findByResident(resident).orElseGet(() -> create(resident));
        if (!manager.community.equals(conversation.community)) throw missing();
        return send(conversation, manager, resident.id, request);
    }

    public DirectMessagingApi.MessageView sendAsManager(String email, Long conversationId, Outgoing request) {
        Account manager = require(email, Account.Role.MANAGER);
        DirectConversation conversation = conversations.findByIdAndCommunity(conversationId, manager.community)
                .orElseThrow(this::missing);
        return send(conversation, manager, residentId(conversation), request);
    }

    @Transactional(readOnly = true)
    public DirectMessagingApi.MessagePage residentMessages(String email, Long beforeId, int size) {
        Account resident = require(email, Account.Role.RESIDENT);
        DirectConversation conversation = conversations.findByResident(resident).orElse(null);
        if (conversation == null) return new DirectMessagingApi.MessagePage(null, List.of(), null, 0);
        if (!resident.community.equals(conversation.community)) throw missing();
        return page(conversation, beforeId, size, messages.countResidentUnread(
                conversation, conversation.residentLastReadMessageId));
    }

    /**
     * The shared manager inbox: one query for the conversations (with their latest-message summary) and one for
     * the unread counts, however many conversations there are. query matches a name, room or the latest message.
     */
    @Transactional(readOnly = true)
    public List<DirectMessagingApi.ConversationSummary> managerConversations(String email, String query, boolean unreadOnly, int limit) {
        Account manager = require(email, Account.Role.MANAGER);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<DirectConversation> rows = conversations.inbox(manager.community, q.isEmpty(), pattern, unreadOnly, PageRequest.of(0, limit));
        Map<Long, Long> unread = new HashMap<>();
        if (!rows.isEmpty())
            for (Object[] row : messages.managerUnreadByConversation(manager.community, rows.stream().map(c -> c.id).toList()))
                unread.put((Long) row[0], ((Number) row[1]).longValue());
        return rows.stream().map(c -> new DirectMessagingApi.ConversationSummary(c.id, c.resident.id, c.resident.name,
                c.resident.room, c.lastMessagePreview, c.lastMessageAt, unread.getOrDefault(c.id, 0L))).toList();
    }

    @Transactional(readOnly = true)
    public DirectMessagingApi.MessagePage managerMessages(String email, Long conversationId, Long beforeId, int size) {
        Account manager = require(email, Account.Role.MANAGER);
        DirectConversation conversation = managerConversation(manager, conversationId);
        return page(conversation, beforeId, size, messages.countManagerUnread(
                conversation, conversation.resident, conversation.managerLastReadMessageId));
    }

    /** Unread messages for the home-page badge: from management for a resident, from all residents for a manager. */
    @Transactional(readOnly = true)
    public long unread(String email) {
        Account account = participant(email);
        if (account.role == Account.Role.MANAGER) return messages.managerUnreadTotal(account.community);
        return conversations.findByResident(account).filter(c -> c.community.equals(account.community))
                .map(c -> messages.countResidentUnread(c, c.residentLastReadMessageId)).orElse(0L);
    }

    public SseEmitter stream(String email) {
        return events.open(participant(email));
    }

    public void residentRead(String email, Long throughMessageId) {
        Account resident = require(email, Account.Role.RESIDENT);
        DirectConversation conversation = residentConversation(resident);
        requireMessage(conversation, throughMessageId);
        if (conversation.residentLastReadMessageId == null || throughMessageId > conversation.residentLastReadMessageId) {
            conversation.residentLastReadMessageId = throughMessageId;
            events.publish(conversation, resident.id, new DirectMessageEvents.Event("read", conversation.id, throughMessageId));
        }
    }

    public void managerRead(String email, Long conversationId, Long throughMessageId) {
        Account manager = require(email, Account.Role.MANAGER);
        DirectConversation conversation = managerConversation(manager, conversationId);
        requireMessage(conversation, throughMessageId);
        if (conversation.managerLastReadMessageId == null || throughMessageId > conversation.managerLastReadMessageId) {
            conversation.managerLastReadMessageId = throughMessageId;
            events.publish(conversation, residentId(conversation), new DirectMessageEvents.Event("read", conversation.id, throughMessageId));
        }
    }

    /** An attachment for the conversation's resident or a manager of its community; anyone else gets 404. */
    @Transactional(readOnly = true)
    public AttachmentFile attachment(String email, Long attachmentId) {
        Account viewer = participant(email);
        DirectAttachment attachment = attachments.findWithConversation(attachmentId).orElseThrow(this::missingAttachment);
        DirectConversation conversation = attachment.message.conversation;
        boolean allowed = viewer.role == Account.Role.MANAGER ? viewer.community.equals(conversation.community)
                : residentId(conversation).equals(viewer.id) && viewer.community.equals(conversation.community);
        if (!allowed) throw missingAttachment();
        return new AttachmentFile(attachment.filename, attachment.contentType, files.read(attachment.storageKey));
    }

    /** Fills the latest-message summary for conversations created before the summary columns existed. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillSummaries() {
        for (DirectConversation conversation : conversations.findByLastMessageIdIsNull())
            messages.findFirstByConversationOrderByCreatedAtDescIdDesc(conversation).ifPresent(latest -> {
                conversation.lastMessageId = latest.id;
                conversation.lastMessageAt = latest.createdAt;
                conversation.lastMessagePreview = preview(latest.content, attachments.findByMessageIds(List.of(latest.id)));
            });
    }

    private DirectConversation create(Account resident) {
        DirectConversation created = new DirectConversation();
        created.resident = resident;
        created.community = resident.community;
        return conversations.saveAndFlush(created);
    }

    private Long residentId(DirectConversation conversation) {
        return org.hibernate.Hibernate.unproxy(conversation.resident, Account.class).id;
    }

    private DirectConversation residentConversation(Account resident) {
        DirectConversation conversation = conversations.findByResident(resident).orElseThrow(this::missing);
        if (!resident.community.equals(conversation.community)) throw missing();
        return conversation;
    }

    private DirectConversation managerConversation(Account manager, Long conversationId) {
        return conversations.findByIdAndCommunity(conversationId, manager.community).orElseThrow(this::missing);
    }

    private void requireMessage(DirectConversation conversation, Long messageId) {
        messages.findByIdAndConversation(messageId, conversation)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Message not found."));
    }

    private DirectMessagingApi.MessagePage page(DirectConversation conversation, Long beforeId, int size, long unread) {
        PageRequest limit = PageRequest.of(0, size + 1);
        List<DirectMessage> fetched = beforeId == null
                ? messages.findByConversationOrderByIdDesc(conversation, limit)
                : messages.findByConversationAndIdLessThanOrderByIdDesc(conversation, beforeId, limit);
        boolean hasMore = fetched.size() > size;
        List<DirectMessage> shown = new ArrayList<>(fetched.subList(0, Math.min(size, fetched.size())));
        Long nextBeforeId = hasMore ? shown.get(shown.size() - 1).id : null;
        Collections.reverse(shown);
        Map<Long, List<DirectAttachment>> byMessage = shown.isEmpty() ? Map.of()
                : attachments.findByMessageIds(shown.stream().map(m -> m.id).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.message.id));
        return new DirectMessagingApi.MessagePage(conversation.id,
                shown.stream().map(m -> DirectMessagingApi.MessageView.of(m, byMessage.getOrDefault(m.id, List.of()))).toList(),
                nextBeforeId, unread);
    }

    private Account require(String email, Account.Role role) {
        Account account = accounts.current(email);
        if (account.role != role || account.status != Account.Status.APPROVED)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Approved " + role.name().toLowerCase() + " access required.");
        return account;
    }

    private Account participant(String email) {
        Account account = accounts.current(email);
        if (account.status != Account.Status.APPROVED || (account.role != Account.Role.RESIDENT && account.role != Account.Role.MANAGER))
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Approved resident or manager access required.");
        return account;
    }

    private RuntimeException missing() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Conversation not found.");
    }

    private RuntimeException missingAttachment() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Attachment not found.");
    }

    /** Inbox preview: the text on one line, plus a short note about attachments. */
    static String preview(String content, List<DirectAttachment> attached) {
        String text = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        if (!attached.isEmpty()) {
            String label = attached.size() > 1 ? attached.size() + " attachments"
                    : attached.get(0).image() ? "Photo" : attached.get(0).filename;
            text = text.isEmpty() ? "📎 " + label : text + " · 📎 " + label;
        }
        return text.length() > 200 ? text.substring(0, 199) + "…" : text;
    }

    private DirectMessagingApi.MessageView send(DirectConversation conversation, Account sender, Long residentId, Outgoing request) {
        String content = request.content() == null ? "" : request.content();
        List<DirectAttachmentFiles.Checked> checked = files.check(request.files());
        if (content.isBlank() && checked.isEmpty())
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Write a message or attach a file.");
        if (content.length() > 1000) throw AccountService.fail(HttpStatus.BAD_REQUEST, "Messages can be at most 1000 characters.");
        if (request.clientRequestId() == null || request.clientRequestId().isBlank() || request.clientRequestId().length() > 255)
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "A client request ID is required.");

        DirectMessage existing = messages.findByConversationAndSenderAndClientRequestId(
                conversation, sender, request.clientRequestId()).orElse(null);
        if (existing != null) {
            if (!existing.content.equals(content))
                throw AccountService.fail(HttpStatus.CONFLICT, "This request ID was already used for different content.");
            return DirectMessagingApi.MessageView.of(existing, attachments.findByMessageIds(List.of(existing.id)));
        }

        DirectMessage message = new DirectMessage();
        message.conversation = conversation;
        message.sender = sender;
        message.clientRequestId = request.clientRequestId();
        message.content = content;
        messages.saveAndFlush(message);
        List<DirectAttachment> saved = new ArrayList<>();
        List<String> keys = files.write(checked);
        for (int i = 0; i < checked.size(); i++) {
            DirectAttachment attachment = new DirectAttachment();
            attachment.message = message;
            attachment.storageKey = keys.get(i);
            attachment.filename = checked.get(i).filename();
            attachment.contentType = checked.get(i).contentType();
            attachment.sizeBytes = checked.get(i).bytes().length;
            saved.add(attachments.save(attachment));
        }
        conversation.lastMessageId = message.id;
        conversation.lastMessageAt = message.createdAt;
        conversation.lastMessagePreview = preview(content, saved);
        events.publish(conversation, residentId, new DirectMessageEvents.Event("message", conversation.id, message.id));
        return DirectMessagingApi.MessageView.of(message, saved);
    }
}
