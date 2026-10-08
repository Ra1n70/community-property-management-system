package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.Locker;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LockerRepository
        extends JpaRepository<Locker, Long> {

    List<Locker> findByCommunityOrderByLockerNumber(
            String community
    );

    Optional<Locker> findByIdAndCommunity(
            Long id,
            String community
    );

    boolean existsByCommunityAndLockerNumber(
            String community,
            String lockerNumber
    );
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    select l
    from Locker l
    where l.id = :id
    """)
    Optional<Locker> lockById(@Param("id") Long id);
}