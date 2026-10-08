package com.cpms.community.residentchat;

import com.cpms.community.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/resident-chats/conversations")
public class ResidentChatApi {
    public record Create(@NotNull Long residentId) {}
    public record Send(@NotBlank @Size(max = 1000) String content,
                       @NotBlank @Size(max = 255) String clientRequestId) {}
    public record Read(@NotNull Long throughMessageId) {}
    public record ConversationView(Long conversationId, Long otherResidentId, String otherResidentName) {}
    public record ConversationSummary(Long conversationId, Long otherResidentId, String otherResidentName,
                                      String lastMessageContent, Instant lastMessageAt, long unreadCount) {}
    public record MessageView(Long messageId, Long conversationId, Long senderId, String senderName,
                              String content, Instant createdAt) {
        static MessageView of(ResidentChatMessage message) {
            var conversation = org.hibernate.Hibernate.unproxy(message.conversation, ResidentConversation.class);
            var sender = org.hibernate.Hibernate.unproxy(message.sender, com.cpms.community.Account.class);
            return new MessageView(message.id, conversation.id, sender.id,
                    sender.name, message.content, message.createdAt);
        }
    }
    public record MessagePage(Long conversationId, List<MessageView> messages, Long nextBeforeId, long unreadCount) {}

    private final ResidentChatService service;

    public ResidentChatApi(ResidentChatService service) {
        this.service = service;
    }

    @PostMapping
    public ConversationView create(Authentication auth, @Valid @RequestBody Create request) {
        return service.create(auth.getName(), request.residentId());
    }

    @GetMapping
    public List<ConversationSummary> list(Authentication auth) {
        return service.list(auth.getName());
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePage history(Authentication auth, @PathVariable Long conversationId,
                               @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "50") int size) {
        if (size < 1 || size > 100 || (beforeId != null && beforeId < 1))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid message page.");
        return service.history(auth.getName(), conversationId, beforeId, size);
    }

    @PostMapping("/{conversationId}/messages")
    public MessageView send(Authentication auth, @PathVariable Long conversationId,
                            @Valid @RequestBody Send request) {
        return service.send(auth.getName(), conversationId, request);
    }

    @PostMapping("/{conversationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(Authentication auth, @PathVariable Long conversationId, @Valid @RequestBody Read request) {
        service.read(auth.getName(), conversationId, request.throughMessageId());
    }
}
