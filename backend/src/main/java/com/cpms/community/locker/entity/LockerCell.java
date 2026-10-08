package com.cpms.community.locker.entity;

import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "locker_cells",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"locker_id", "cell_number"}
        )
)
public class LockerCell {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "locker_id", nullable = false)
    public Locker locker;

    @Column(name = "cell_number", nullable = false)
    public String cellNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CellSize size;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CellStatus status = CellStatus.AVAILABLE;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Version
    public Long version;

    public LockerCell() {}
}
