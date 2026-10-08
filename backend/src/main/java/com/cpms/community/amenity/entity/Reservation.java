package com.cpms.community.amenity.entity;

import com.cpms.community.amenity.enums.ReservationStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "reservations",
        uniqueConstraints = @UniqueConstraint(name = "uk_reservations_slot_key", columnNames = "slot_key"),
        indexes = @Index(name = "idx_reservations_amenity_start", columnList = "amenity_id, start_at")
)
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long amenityId;

    public Long accountId;

    @Column(nullable = false)
    public String community;

    @Column(nullable = false, length = 30)
    public String room;

    @Column(nullable = false, length = 100)
    public String guestName;

    @Column(nullable = false)
    public Instant startAt;

    @Column(nullable = false)
    public Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ReservationStatus status = ReservationStatus.UPCOMING;

    /** Fee copied from the amenity when booked, in USD with two decimal places. */
    @Column(nullable = false, precision = 10, scale = 2)
    public BigDecimal fee = BigDecimal.ZERO.setScale(2);

    @Column(length = 1000)
    public String cancelReason;

    public String cancelledBy;
    public Instant cancelledAt;

    public String slotKey;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Version
    public Long version;

    public Reservation() {}
}
