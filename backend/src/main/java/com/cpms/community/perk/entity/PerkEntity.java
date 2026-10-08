package com.cpms.community.perk.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "local_perks", indexes = {
        @Index(name = "idx_local_perks_community", columnList = "community"),
        @Index(name = "idx_local_perks_category", columnList = "category")
})
public class PerkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    @Column(nullable = false, length = 60)
    public String businessName;

    @Column(nullable = false, length = 80)
    public String title;

    @Column(nullable = false, length = 1000)
    public String description;

    @Column(nullable = false, length = 30)
    public String category;

    @Column(length = 40)
    public String contact;

    @Column(length = 160)
    public String address;

    @Column(length = 200)
    public String website;

    public Instant startAt;

    @Column(nullable = false)
    public Instant endAt;

    @Column(nullable = false)
    public boolean published;

    public Long createdById;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    @Version
    public Long version;

    public PerkEntity() {
    }
}
