package com.cpms.community.directmessage;

import com.cpms.community.Account;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "direct_conversations",
        uniqueConstraints = @UniqueConstraint(name = "uk_direct_conversations_resident", columnNames = "resident_id"),
        indexes = {@Index(name = "idx_direct_conversations_community", columnList = "community"),
                @Index(name = "idx_direct_conversations_latest", columnList = "community, last_message_at")})
public class DirectConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resident_id", nullable = false) public Account resident;
    @Column(nullable = false) public String community;
    @Column(nullable = false) public Instant createdAt = Instant.now();
    public Long residentLastReadMessageId;
    public Long managerLastReadMessageId;
    /** Latest message summary, kept up to date on send so the inbox needs no per-conversation lookups. */
    public Long lastMessageId;
    public Instant lastMessageAt;
    @Column(length = 200) public String lastMessagePreview;
    public DirectConversation() {}
}
