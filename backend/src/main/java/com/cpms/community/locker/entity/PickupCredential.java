package com.cpms.community.locker.entity;

import com.cpms.community.locker.enums.PickupCredentialStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "pickup_credentials")
public class PickupCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parcel_id", nullable = false)
    public Parcel parcel;

    @Column(
            name = "code_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    public String codeHash;
    @Column(name = "code_nonce", unique = true, length = 36)
    public String codeNonce;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PickupCredentialStatus status =
            PickupCredentialStatus.ACTIVE;

    @Column(name = "failed_attempts", nullable = false)
    public int failedAttempts = 0;

    @Column(name = "locked_until")
    public Instant lockedUntil;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "used_at")
    public Instant usedAt;

    @Column(name = "invalidated_at")
    public Instant invalidatedAt;

    @Version
    public Long version;

    public PickupCredential() {}
}
