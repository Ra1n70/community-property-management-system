package com.cpms.community.discussion.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "comments", indexes = {
        @Index(name = "idx_comments_discussion", columnList = "discussion_id"),
        @Index(name = "idx_comments_community", columnList = "community")
})
public class CommentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    @Column(nullable = false)
    public Long discussionId;

    @Column(nullable = false)
    public Long authorId;

    @Column(nullable = false)
    public String authorName;

    @Column(nullable = false)
    public String authorRole;

    @Column(nullable = false, length = 1000)
    public String content;

    @Column(nullable = false)
    public int likeCount;

    @Column(nullable = false)
    public boolean deleted;

    @Column(length = 500)
    public String deleteReason;

    public Long deletedById;

    public Instant deletedAt;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    @Version
    public Long version;

    public CommentEntity() {
    }
}
