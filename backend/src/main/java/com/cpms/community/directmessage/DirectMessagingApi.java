package com.cpms.community.directmessage;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/direct-messages")
public class DirectMessagingApi {
    public record Send(@NotBlank @Size(max = 1000) String content,
                       @NotBlank @Size(max = 255) String clientRequestId) {}
    /** Multipart variant: text may be empty when files are attached. */
    public record SendWithFiles(@Size(max = 1000) String content, @NotBlank @Size(max = 255) String clientRequestId) {}
    public record Read(@NotNull Long throughMessageId) {}

    public record AttachmentView(Long id, String filename, String contentType, long size, boolean image) {
        static AttachmentView of(DirectAttachment a) { return new AttachmentView(a.id, a.filename, a.contentType, a.sizeBytes, a.image()); }
    }
    public record MessageView(Long messageId, Long conversationId, Long senderId, String senderName,
                              Account.Role senderRole, String content, Instant createdAt, List<AttachmentView> attachments) {
        static MessageView of(DirectMessage message, List<DirectAttachment> attached) {
            Account sender = org.hibernate.Hibernate.unproxy(message.sender, Account.class);
            DirectConversation conversation = org.hibernate.Hibernate.unproxy(message.conversation, DirectConversation.class);
            return new MessageView(message.id, conversation.id, sender.id, sender.name,
                    sender.role, message.content, message.createdAt, attached.stream().map(AttachmentView::of).toList());
        }
    }
    public record MessagePage(Long conversationId, List<MessageView> messages, Long nextBeforeId, long unreadCount) {}
    public record ConversationSummary(Long conversationId, Long residentId, String residentName, String residentRoom,
                                      String lastMessageContent, Instant lastMessageAt, long unreadCount) {}

    private final DirectMessagingService service;

    public DirectMessagingApi(DirectMessagingService service) {
        this.service = service;
    }

    private static DirectMessagingService.Outgoing upload(SendWithFiles request, List<MultipartFile> files) {
        return new DirectMessagingService.Outgoing(request.content(), request.clientRequestId(), files == null ? List.of() : files);
    }

    @PostMapping(value = "/me/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MessageView sendAsResident(Authentication auth, @Valid @RequestBody Send request) {
        return service.sendAsResident(auth.getName(), DirectMessagingService.Outgoing.of(request));
    }

    @PostMapping(value = "/me/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageView sendAsResidentWithFiles(Authentication auth, @Valid @RequestPart("request") SendWithFiles request,
                                               @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return service.sendAsResident(auth.getName(), upload(request, files));
    }

    @PostMapping(value = "/conversations/{conversationId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MessageView sendAsManager(Authentication auth, @PathVariable Long conversationId,
                                     @Valid @RequestBody Send request) {
        return service.sendAsManager(auth.getName(), conversationId, DirectMessagingService.Outgoing.of(request));
    }

    @PostMapping(value = "/conversations/{conversationId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageView sendAsManagerWithFiles(Authentication auth, @PathVariable Long conversationId,
                                              @Valid @RequestPart("request") SendWithFiles request,
                                              @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return service.sendAsManager(auth.getName(), conversationId, upload(request, files));
    }

    @PostMapping(value = "/residents/{residentId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MessageView sendAsManagerToResident(Authentication auth, @PathVariable Long residentId,
                                               @Valid @RequestBody Send request) {
        return service.sendAsManagerToResident(auth.getName(), residentId, DirectMessagingService.Outgoing.of(request));
    }

    @PostMapping(value = "/residents/{residentId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageView sendAsManagerToResidentWithFiles(Authentication auth, @PathVariable Long residentId,
                                                        @Valid @RequestPart("request") SendWithFiles request,
                                                        @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return service.sendAsManagerToResident(auth.getName(), residentId, upload(request, files));
    }

    @GetMapping("/me/messages")
    public MessagePage residentMessages(Authentication auth, @RequestParam(required = false) Long beforeId,
                                        @RequestParam(defaultValue = "50") int size) {
        validatePage(beforeId, size);
        return service.residentMessages(auth.getName(), beforeId, size);
    }

    /** q matches a resident name, room or the latest message; unreadOnly keeps conversations with unread messages. */
    @GetMapping("/conversations")
    public List<ConversationSummary> managerConversations(Authentication auth, @RequestParam(defaultValue = "") String q,
                                                          @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                          @RequestParam(defaultValue = "100") int limit) {
        if (q.length() > 100 || limit < 1 || limit > 500) throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid conversation search.");
        return service.managerConversations(auth.getName(), q, unreadOnly, limit);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MessagePage managerMessages(Authentication auth, @PathVariable Long conversationId,
                                       @RequestParam(required = false) Long beforeId,
                                       @RequestParam(defaultValue = "50") int size) {
        validatePage(beforeId, size);
        return service.managerMessages(auth.getName(), conversationId, beforeId, size);
    }

    @GetMapping("/unread")
    public Map<String, Long> unread(Authentication auth) {
        return Map.of("unread", service.unread(auth.getName()));
    }

    /** Server-Sent Events: "dm" events {type, conversationId, messageId} when a message arrives or is read. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        return service.stream(auth.getName());
    }

    /** Photos open in the browser; PDFs download. Both are private to the conversation. */
    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(Authentication auth, @PathVariable Long attachmentId) {
        DirectMessagingService.AttachmentFile file = service.attachment(auth.getName(), attachmentId);
        boolean image = file.contentType().startsWith("image/");
        ContentDisposition disposition = (image ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @PostMapping("/me/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void residentRead(Authentication auth, @Valid @RequestBody Read request) {
        service.residentRead(auth.getName(), request.throughMessageId());
    }

    @PostMapping("/conversations/{conversationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void managerRead(Authentication auth, @PathVariable Long conversationId,
                            @Valid @RequestBody Read request) {
        service.managerRead(auth.getName(), conversationId, request.throughMessageId());
    }

    private void validatePage(Long beforeId, int size) {
        if (size < 1 || size > 100 || (beforeId != null && beforeId < 1))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid message page.");
    }
}
