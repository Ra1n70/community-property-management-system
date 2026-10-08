package com.cpms.community;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class RecoveryLink {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long accountId;
    public Long managerId;
    public String tokenHash;
    @Column(length=1000) public String verificationNote;
    public Instant createdAt = Instant.now();
    public Instant expiresAt;
    public Instant usedAt;
    public Instant revokedAt;
}
