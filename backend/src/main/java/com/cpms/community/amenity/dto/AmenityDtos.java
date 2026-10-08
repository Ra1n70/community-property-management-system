package com.cpms.community.amenity.dto;

import com.cpms.community.amenity.entity.Amenity;
import jakarta.validation.constraints.*;

public final class AmenityDtos {
    private AmenityDtos() {}

    public record AmenityInput(
            @NotBlank @Size(max = 25) String name,
            @NotBlank @Size(max = 50) String type,
            @NotBlank @Size(max = 100) String location,
            @Min(1) int capacity,
            @Min(15) @Max(240) int slotDurationMinutes,
            boolean chargeable,
            @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) java.math.BigDecimal fee,
            @Size(max = 1000) String description,
            @Min(1) @Max(24) Integer maxSlotsPerDay,
            @Min(1) @Max(365) Integer maxAdvanceDays
    ) {}

    public record HourRange(
            @NotNull java.time.DayOfWeek dayOfWeek,
            @NotNull java.time.LocalTime openTime,
            @NotNull java.time.LocalTime closeTime
    ) {}

    public record ClosureView(Long id, java.time.Instant startAt, java.time.Instant endAt, String reason) {}

    public record ReplaceHours(
            @NotNull java.util.List<@jakarta.validation.Valid HourRange> hours
    ) {}

    public record AmenityView(
            Long id,
            String community,
            String name,
            String type,
            String location,
            int capacity,
            int slotDurationMinutes,
            boolean chargeable,
            java.math.BigDecimal fee,
            String description,
            int maxSlotsPerDay,
            int maxAdvanceDays,
            String imageUrl,
            boolean hasAvailableSlotsToday
    ) {
        public static AmenityView of(Amenity amenity, String imageUrl, boolean hasAvailableSlotsToday) {
            return new AmenityView(
                    amenity.id,
                    amenity.community,
                    amenity.name,
                    amenity.type,
                    amenity.location,
                    amenity.capacity,
                    amenity.slotDurationMinutes,
                    amenity.chargeable,
                    amenity.fee,
                    amenity.description,
                    amenity.maxSlotsPerDay,
                    amenity.maxAdvanceDays,
                    imageUrl,
                    hasAvailableSlotsToday
            );
        }
    }
}
