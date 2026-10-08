package com.cpms.community.amenity.dto;

import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.enums.ReservationStatus;
import com.cpms.community.amenity.enums.SlotStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ReservationDtos {
    private ReservationDtos() {}

    /** remaining: households that can still book this slot (0 unless AVAILABLE). */
    public record SlotView(Instant startAt, Instant endAt, SlotStatus status, int remaining) {}

    public record CreateReservation(
            String community,
            @NotBlank @Size(max = 30) String room,
            @NotBlank @Size(max = 100) String guestName,
            @NotNull Long amenityId,
            @NotNull Instant startAt
    ) {}

    public record CancelReservation(
            @Size(max = 1000) String reason
    ) {}

    public record ReservationView(
            Long id,
            Long amenityId,
            String amenityName,
            String community,
            String room,
            String guestName,
            Instant startAt,
            Instant endAt,
            ReservationStatus status,
            java.math.BigDecimal fee,
            String cancelReason,
            Instant createdAt
    ) {
        public static ReservationView of(Reservation reservation, String amenityName, Instant now) {
            ReservationStatus status = reservation.status;
            if (status == ReservationStatus.UPCOMING && !reservation.endAt.isAfter(now)) {
                status = ReservationStatus.COMPLETED;
            }
            return new ReservationView(
                    reservation.id,
                    reservation.amenityId,
                    amenityName,
                    reservation.community,
                    reservation.room,
                    reservation.guestName,
                    reservation.startAt,
                    reservation.endAt,
                    status,
                    reservation.fee,
                    reservation.cancelReason,
                    reservation.createdAt
            );
        }
    }
}
