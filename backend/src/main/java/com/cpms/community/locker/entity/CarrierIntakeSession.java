package com.cpms.community.locker.entity;

import com.cpms.community.Account;
import com.cpms.community.locker.enums.CellSize;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "carrier_intake_sessions")
public class CarrierIntakeSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public LockerCell cell;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public Account resident;

    @Column(nullable = false, unique = true, length = 64)
    public String tokenHash;

    @Column(nullable = false)
    public String carrierName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CellSize packageSize;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant expiresAt;

    public Instant completedAt;
    public Instant expiredAt;
    public Long registeredBy;
    public Long courierId;
    public String courierName;

    public CarrierIntakeSession() {}
}
