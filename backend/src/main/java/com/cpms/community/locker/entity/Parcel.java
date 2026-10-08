package com.cpms.community.locker.entity;

import com.cpms.community.Account;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.IntakeSource;
import com.cpms.community.locker.enums.ParcelStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "parcels", indexes = {
        @Index(name = "idx_parcels_community_stored", columnList = "community, stored_at"),
        @Index(name = "idx_parcels_resident_stored", columnList = "resident_id, stored_at"),
        @Index(name = "idx_parcels_registered_stored", columnList = "registered_by, stored_at")
})
public class Parcel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resident_id", nullable = false)
    public Account resident;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cell_id", nullable = false)
    public LockerCell cell;

    @Column(name = "carrier_name", nullable = false)
    public String carrierName;

    @Column(name = "tracking_number")
    public String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_size", nullable = false)
    public CellSize packageSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ParcelStatus status = ParcelStatus.PENDING_PICKUP;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_source", nullable = false)
    public IntakeSource intakeSource;

    @Column(name = "registered_by")
    public Long registeredBy;

    /** Kiosk courier who stored the package (registeredBy is then their delivery company, if any). */
    @Column(name = "courier_id")
    public Long courierId;

    @Column(name = "courier_name")
    public String courierName;

    @Column(name = "stored_at", nullable = false)
    public Instant storedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "picked_up_at")
    public Instant pickedUpAt;

    @Column(name = "retrieved_at")
    public Instant retrievedAt;

    @Version
    public Long version;

    public Parcel() {}
}
