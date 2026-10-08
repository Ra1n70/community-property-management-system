package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.PickupCredential;
import com.cpms.community.locker.enums.PickupCredentialStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PickupCredentialRepository
        extends JpaRepository<PickupCredential, Long> {

    boolean existsByCodeHash(String codeHash);

    List<PickupCredential>
    findByParcelIdOrderByCreatedAtDesc(Long parcelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c
        from PickupCredential c
        where c.codeHash = :codeHash
        """)
    Optional<PickupCredential> lockByCodeHash(
            @Param("codeHash") String codeHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c
        from PickupCredential c
        where c.parcel.id = :parcelId
          and c.status = :status
        """)
    List<PickupCredential> lockByParcelIdAndStatus(
            @Param("parcelId") Long parcelId,
            @Param("status")
            PickupCredentialStatus status
    );
    Optional<PickupCredential>
    findFirstByParcelIdAndStatusOrderByCreatedAtDesc(
            Long parcelId,
            PickupCredentialStatus status
    );
}
