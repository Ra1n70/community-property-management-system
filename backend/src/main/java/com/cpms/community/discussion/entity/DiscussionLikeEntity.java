package com.cpms.community.discussion.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "discussion_likes", uniqueConstraints =
        @UniqueConstraint(name = "uk_discussion_like", columnNames = {"discussion_id", "account_id"}))
public class DiscussionLikeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "discussion_id", nullable = false)
    public Long discussionId;

    @Column(name = "account_id", nullable = false)
    public Long accountId;

    @Column(nullable = false)
    public Instant createdAt;

    public DiscussionLikeEntity() {
    }
}
