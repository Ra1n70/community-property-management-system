package com.cpms.community.announcement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Version;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "announcements", indexes = @Index(name = "idx_announcements_community", columnList = "community"))
public class Announcement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 100)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = true)
    private Instant publishedAt;

    // Nullable for pre-existing standalone rows; unscoped rows are never returned by community queries.
    private String community;
    private Long authorId;
    @Version private Long version;
    public String getCommunity() { return community; }
    public Long getAuthorId() { return authorId; }
    public Long getVersion() { return version; }

    protected Announcement() {}

    public Announcement(String title, String author, Instant publishedAt, String content, String community, Long authorId) {
        this.community = community;
        this.authorId = authorId;
        this.title = title;
        this.author = author;
        this.publishedAt = publishedAt;
        this.content = content;
    }

    @PrePersist
    void beforeInsert() {
        if (publishedAt == null) publishedAt = Instant.now();
    }

    public void update(String title, Instant publishedAt, String content) {
        this.title = title;
        this.publishedAt = publishedAt;
        this.content = content;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getAuthor() { return author; }
    public Instant getPublishedAt() { return publishedAt; }
}
