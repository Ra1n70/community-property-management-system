package com.cpms.community.locker.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * An individual courier who stores packages at the kiosk with a personal store code.
 * Couriers belong to a delivery company account (which manages their codes) or, when
 * companyId is null, are independent couriers managed by the property manager.
 */
@Entity
@Table(name = "couriers")
public class Courier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    /** Delivery company provider account; null for an independent courier. */
    public Long companyId;

    public String companyName;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(length = 30)
    public String phone;

    /** HMAC of the store code, used to look the courier up at the kiosk. */
    @Column(nullable = false, unique = true, length = 64)
    public String codeHash;

    /** Random seed; the code is re-derived from it with the server secret so its manager can view it again. */
    public String codeNonce;

    @Column(nullable = false, length = 4)
    public String codeHint;

    @Column(nullable = false)
    public boolean active = true;

    /** Set for a manager-issued temporary code (a walk-in courier); the code stops working at this time. */
    public Instant expiresAt;

    @Column(nullable = false)
    public Long createdBy;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();

    @Version
    public Long version;
}
