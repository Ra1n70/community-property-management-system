package com.cpms.community.amenity.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "amenities", indexes = @Index(name = "idx_amenities_community", columnList = "community"))
public class Amenity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    @Column(nullable = false, length = 25)
    public String name;

    @Column(nullable = false)
    public String type;

    @Column(nullable = false)
    public String location;

    @Column(nullable = false)
    public int capacity;

    @Column(nullable = false)
    public int slotDurationMinutes;

    @Column(nullable = false)
    public boolean chargeable;

    /** Fee per session in USD, always two decimal places; zero when not chargeable. */
    @Column(nullable = false, precision = 10, scale = 2)
    public BigDecimal fee = BigDecimal.ZERO.setScale(2);

    @Column(length = 1000)
    public String description;

    @Column(nullable = false)
    public int maxSlotsPerDay = 2;

    @Column(nullable = false)
    public int maxAdvanceDays = 7;

    public String imageFilename;
    public String imageContentType;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();

    @Version
    public Long version;

    public Amenity() {}
}
