package com.cpms.community.directmessage;

import jakarta.persistence.*;

/** A photo or PDF sent with a direct message. The file itself is stored on disk under storageKey. */
@Entity
@Table(name = "direct_attachments", indexes = @Index(name = "idx_direct_attachments_message", columnList = "message_id"))
public class DirectAttachment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false) public DirectMessage message;
    @Column(nullable = false, unique = true, length = 64) public String storageKey;
    @Column(nullable = false, length = 255) public String filename;
    @Column(nullable = false, length = 100) public String contentType;
    @Column(nullable = false) public long sizeBytes;
    public DirectAttachment() {}

    public boolean image() { return contentType.startsWith("image/"); }
}
