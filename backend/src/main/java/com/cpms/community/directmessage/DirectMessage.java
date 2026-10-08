package com.cpms.community.directmessage;

import com.cpms.community.Account;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "direct_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_direct_messages_request",
                columnNames = {"conversation_id", "sender_id", "client_request_id"}),
        indexes = {@Index(name = "idx_direct_messages_conversation_time", columnList = "conversation_id, created_at, id"),
                @Index(name = "idx_direct_messages_conversation_id", columnList = "conversation_id, id")})
public class DirectMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false) public DirectConversation conversation;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false) public Account sender;
    @Column(nullable = false) public String clientRequestId;
    /** May be empty when the message only carries attachments. */
    @Column(nullable = false, length = 1000) public String content;
    @Column(nullable = false) public Instant createdAt = Instant.now();
    public DirectMessage() {}
}
