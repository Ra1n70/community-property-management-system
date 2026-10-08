package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.LockerPickupAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LockerPickupAttemptRepository
        extends JpaRepository<LockerPickupAttempt, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select a
        from LockerPickupAttempt a
        where a.locker.id = :lockerId
        """)
    Optional<LockerPickupAttempt> lockByLockerId(
            @Param("lockerId") Long lockerId
    );
}
