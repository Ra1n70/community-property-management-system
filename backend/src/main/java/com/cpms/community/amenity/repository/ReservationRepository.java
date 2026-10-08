package com.cpms.community.amenity.repository;

import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByAmenityIdAndStatusAndStartAtGreaterThanEqualAndStartAtLessThan(
            Long amenityId,
            ReservationStatus status,
            Instant startInclusive,
            Instant startExclusive
    );

    List<Reservation> findByAmenityIdAndRoomAndStatusNotAndStartAtGreaterThanEqualAndStartAtLessThan(
            Long amenityId,
            String room,
            ReservationStatus excluded,
            Instant startInclusive,
            Instant startExclusive
    );

    List<Reservation> findByCommunityAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
            String community,
            Instant startInclusive,
            Instant startExclusive
    );

    List<Reservation> findByCommunityAndAmenityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
            String community,
            Long amenityId,
            Instant startInclusive,
            Instant startExclusive
    );

    Optional<Reservation> findByIdAndCommunity(Long id, String community);

    List<Reservation> findByAmenityIdAndStatusAndStartAtLessThanAndEndAtGreaterThan(
            Long amenityId,
            ReservationStatus status,
            Instant endAt,
            Instant startAt
    );
    List<Reservation> findByAmenityIdAndStatusAndEndAtGreaterThan(Long amenityId, ReservationStatus status, Instant after);
    List<Reservation> findByCommunityAndAccountIdOrderByStartAtDesc(String community,Long accountId);
    Optional<Reservation> findByIdAndCommunityAndAccountId(Long id,String community,Long accountId);
}
