package com.cpms.community.residentchat;

import com.cpms.community.Account;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "resident_conversations",
        uniqueConstraints = @UniqueConstraint(name = "uk_resident_conversation_pair",
                columnNames = {"resident_one_id", "resident_two_id"}),
        indexes = {@Index(name = "idx_resident_conversation_one", columnList = "resident_one_id"),
                @Index(name = "idx_resident_conversation_two", columnList = "resident_two_id")})
public class ResidentConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resident_one_id", nullable = false) public Account residentOne;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resident_two_id", nullable = false) public Account residentTwo;
    @Column(nullable = false) public String community;
    public Long residentOneLastReadMessageId;
    public Long residentTwoLastReadMessageId;
    @Column(nullable = false) public Instant createdAt = Instant.now();
    public ResidentConversation() {}
}
