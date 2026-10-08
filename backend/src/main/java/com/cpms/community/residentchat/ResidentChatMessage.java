package com.cpms.community.residentchat;

import com.cpms.community.Account;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "resident_chat_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_resident_chat_message_request",
                columnNames = {"conversation_id", "sender_id", "client_request_id"}),
        indexes = @Index(name = "idx_resident_chat_messages_conversation_id", columnList = "conversation_id, id"))
public class ResidentChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false) public ResidentConversation conversation;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false) public Account sender;
    @Column(nullable = false, length = 1000) public String content;
    @Column(nullable = false, length = 255) public String clientRequestId;
    @Column(nullable = false) public Instant createdAt = Instant.now();
    public ResidentChatMessage() {}
}
