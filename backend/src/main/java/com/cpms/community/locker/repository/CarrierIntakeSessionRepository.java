package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.CarrierIntakeSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CarrierIntakeSessionRepository
        extends JpaRepository<CarrierIntakeSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from CarrierIntakeSession s
            where s.tokenHash = :tokenHash
            """)
    Optional<CarrierIntakeSession> lockByTokenHash(
            @Param("tokenHash") String tokenHash
    );

    @Query("""
            select s.id from CarrierIntakeSession s
            where s.completedAt is null
              and s.expiredAt is null
              and s.expiresAt <= :now
            """)
    List<Long> findExpiredIds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CarrierIntakeSession s where s.id = :id")
    Optional<CarrierIntakeSession> lockById(@Param("id") Long id);
    boolean existsByCellId(Long cellId);
}
