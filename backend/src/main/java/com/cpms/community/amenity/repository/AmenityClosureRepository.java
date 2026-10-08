package com.cpms.community.amenity.repository;

import com.cpms.community.amenity.entity.AmenityClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AmenityClosureRepository extends JpaRepository<AmenityClosure, Long> {
    @Query("""
            select c from AmenityClosure c
            where c.amenityId = :amenityId
              and c.startAt < :endAt
              and c.endAt > :startAt
            """)
    List<AmenityClosure> findOverlapping(
            @Param("amenityId") Long amenityId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt
    );

    List<AmenityClosure> findByAmenityIdAndEndAtGreaterThanOrderByStartAtAsc(Long amenityId, Instant after);

    Optional<AmenityClosure> findByIdAndAmenityId(Long id, Long amenityId);
}
