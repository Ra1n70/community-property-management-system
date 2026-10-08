package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.PickupEmailJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PickupEmailJobRepository
        extends JpaRepository<PickupEmailJob, Long> {

    @Query("""
            select j.id from PickupEmailJob j
            where j.sentAt is null
              and j.cancelledAt is null
              and j.dueAt <= :now
              and j.nextAttemptAt <= :now
            order by j.nextAttemptAt, j.id
            """)
    List<Long> findReadyIds(
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from PickupEmailJob j where j.id = :id")
    Optional<PickupEmailJob> lockById(@Param("id") Long id);
}
