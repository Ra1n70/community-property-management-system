package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.enums.LockerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LockerCellRepository
        extends JpaRepository<LockerCell, Long> {

    List<LockerCell> findByLockerIdOrderByCellNumber(
            Long lockerId
    );

    Optional<LockerCell> findByIdAndLockerCommunity(
            Long id,
            String community
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c
        from LockerCell c
        where c.id = :id
          and c.locker.community = :community
        """)
    Optional<LockerCell> lockByIdAndCommunity(
            @Param("id") Long id,
            @Param("community") String community
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c
        from LockerCell c
        where c.locker.id = :lockerId
          and c.locker.community = :community
          and c.locker.status = :lockerStatus
          and c.size = :size
          and c.status = :cellStatus
        order by c.id
        """)
    List<LockerCell> lockAvailableCells(
            @Param("lockerId") Long lockerId,
            @Param("community") String community,
            @Param("lockerStatus") LockerStatus lockerStatus,
            @Param("size") CellSize size,
            @Param("cellStatus") CellStatus cellStatus,
            Pageable pageable
    );
    boolean existsByLockerIdAndCellNumber(
            Long lockerId,
            String cellNumber
    );
    boolean existsByLockerIdAndStatusIn(
            Long lockerId,
            java.util.Collection<CellStatus> statuses
    );
}
