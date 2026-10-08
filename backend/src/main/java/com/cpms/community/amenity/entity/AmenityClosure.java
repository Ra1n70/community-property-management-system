package com.cpms.community.amenity.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "amenity_closures", indexes = @Index(name = "idx_closures_amenity_start", columnList = "amenity_id, start_at"))
public class AmenityClosure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long amenityId;

    @Column(nullable = false)
    public Instant startAt;

    @Column(nullable = false)
    public Instant endAt;

    @Column(nullable = false, length = 1000)
    public String reason;

    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public AmenityClosure() {}
}
