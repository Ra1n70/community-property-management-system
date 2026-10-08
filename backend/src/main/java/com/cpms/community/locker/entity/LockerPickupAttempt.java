package com.cpms.community.locker.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "locker_pickup_attempts")
public class LockerPickupAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "locker_id", nullable = false, unique = true)
    public Locker locker;

    @Column(nullable = false)
    public int consecutiveFailures = 0;

    public Instant lockedUntil;

    @Version
    public Long version;

    public LockerPickupAttempt() {}
}
