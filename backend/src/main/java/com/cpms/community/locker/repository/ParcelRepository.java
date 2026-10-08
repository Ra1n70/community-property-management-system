package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.ParcelStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ParcelRepository
        extends JpaRepository<Parcel, Long>, JpaSpecificationExecutor<Parcel> {

    // Paged lists load the resident, cell and locker with the page instead of one query per row.
    @Override
    @EntityGraph(attributePaths = {"resident", "cell", "cell.locker"})
    org.springframework.data.domain.Page<Parcel> findAll(
            org.springframework.data.jpa.domain.Specification<Parcel> spec,
            org.springframework.data.domain.Pageable pageable
    );

    List<Parcel> findByResidentIdOrderByStoredAtDesc(
            Long residentId
    );

    Optional<Parcel> findByIdAndResidentId(
            Long id,
            Long residentId
    );

    List<Parcel> findByCommunityOrderByStoredAtDesc(
            String community
    );

    List<Parcel> findByCommunityAndStatusOrderByStoredAtDesc(
            String community,
            ParcelStatus status
    );

    List<Parcel>
    findByCommunityAndCellLockerIdOrderByStoredAtDesc(
            String community,
            Long lockerId
    );

    List<Parcel> findByStatusAndExpiresAtBefore(
            ParcelStatus status,
            Instant expiresAt
    );
    List<Parcel> findByCommunityAndStatusAndCellLockerIdOrderByStoredAtDesc(
            String community,
            ParcelStatus status,
            Long lockerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Parcel p
        where p.id = :id
          and p.community = :community
        """)
    Optional<Parcel> lockByIdAndCommunity(
            @Param("id") Long id,
            @Param("community") String community
    );
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Parcel p where p.id = :id")
    Optional<Parcel> lockById(@Param("id") Long id);
    boolean existsByCellId(Long cellId);
    List<Parcel> findByRegisteredByAndIntakeSourceOrderByStoredAtDesc(Long registeredBy, com.cpms.community.locker.enums.IntakeSource intakeSource);
}
