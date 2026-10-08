package com.cpms.community.amenity.entity;

import jakarta.persistence.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(name = "amenity_weekly_hours")
public class AmenityWeeklyHour {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long amenityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DayOfWeek dayOfWeek;

    @Column(nullable = false)
    public LocalTime openTime;

    @Column(nullable = false)
    public LocalTime closeTime;

    public AmenityWeeklyHour() {}

    public AmenityWeeklyHour(Long amenityId, DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime) {
        this.amenityId = amenityId;
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }
}
