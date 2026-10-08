package com.cpms.community.locker.entity;

import com.cpms.community.locker.enums.PickupEmailType;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "pickup_email_jobs")
public class PickupEmailJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false)
    public PickupCredential credential;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PickupEmailType type;

    @Column(nullable = false)
    public Instant dueAt;

    @Column(nullable = false)
    public Instant nextAttemptAt;

    @Column(nullable = false)
    public int attempts = 0;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant lastAttemptAt;
    public Instant sentAt;
    public Instant cancelledAt;

    @Version
    public Long version;

    public PickupEmailJob() {}
}
