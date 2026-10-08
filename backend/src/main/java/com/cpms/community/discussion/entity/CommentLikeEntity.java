package com.cpms.community.discussion.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "comment_likes", uniqueConstraints =
        @UniqueConstraint(name = "uk_comment_like", columnNames = {"comment_id", "account_id"}))
public class CommentLikeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "comment_id", nullable = false)
    public Long commentId;

    @Column(name = "account_id", nullable = false)
    public Long accountId;

    @Column(nullable = false)
    public Instant createdAt;

    public CommentLikeEntity() {
    }
}
