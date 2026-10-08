package com.cpms.community.locker.entity;

import com.cpms.community.locker.enums.LockerStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "lockers",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"community", "locker_number"}
        )
)
public class Locker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    @Column(name = "locker_number", nullable = false)
    public String lockerNumber;

    @Column(nullable = false)
    public String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public LockerStatus status = LockerStatus.ACTIVE;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Version
    public Long version;

    public Locker() {}
}
