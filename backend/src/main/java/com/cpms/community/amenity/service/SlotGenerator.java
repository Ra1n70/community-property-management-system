package com.cpms.community.amenity.service;

import com.cpms.community.amenity.AmenityConfig;
import com.cpms.community.amenity.dto.ReservationDtos.SlotView;
import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.amenity.entity.AmenityClosure;
import com.cpms.community.amenity.entity.AmenityWeeklyHour;
import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.enums.ReservationStatus;
import com.cpms.community.amenity.enums.SlotStatus;
import com.cpms.community.amenity.repository.AmenityClosureRepository;
import com.cpms.community.amenity.repository.AmenityWeeklyHourRepository;
import com.cpms.community.amenity.repository.ReservationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class SlotGenerator {
    private final AmenityWeeklyHourRepository hours;
    private final AmenityClosureRepository closures;
    private final ReservationRepository reservations;

    public SlotGenerator(
            AmenityWeeklyHourRepository hours,
            AmenityClosureRepository closures,
            ReservationRepository reservations
    ) {
        this.hours = hours;
        this.closures = closures;
        this.reservations = reservations;
    }

    public List<SlotView> generate(Amenity amenity, LocalDate date, Instant now) {
        ZonedDateTime dayStart = date.atStartOfDay(AmenityConfig.ZONE);
        Instant rangeStart = dayStart.toInstant();
        Instant rangeEnd = dayStart.plusDays(1).toInstant();
        List<AmenityWeeklyHour> openHours = hours.findByAmenityIdAndDayOfWeekOrderByOpenTimeAsc(
                amenity.id,
                date.getDayOfWeek()
        );
        List<AmenityClosure> closed = closures.findOverlapping(amenity.id, rangeStart, rangeEnd);
        List<Reservation> booked = reservations.findByAmenityIdAndStatusAndStartAtGreaterThanEqualAndStartAtLessThan(
                amenity.id,
                ReservationStatus.UPCOMING,
                rangeStart,
                rangeEnd
        );
        List<SlotView> slots = new ArrayList<>();
        for (AmenityWeeklyHour hour : openHours) {
            LocalTime cursor = hour.openTime;
            LocalTime close = hour.closeTime;
            int duration = amenity.slotDurationMinutes;
            while (java.time.Duration.between(cursor, close).toMinutes() >= duration) {
                LocalTime end = cursor.plusMinutes(duration);
                Instant startAt = ZonedDateTime.of(date, cursor, AmenityConfig.ZONE).toInstant();
                Instant endAt = ZonedDateTime.of(date, end, AmenityConfig.ZONE).toInstant();
                int remaining = Math.max(0, amenity.capacity - overlapping(startAt, endAt, booked));
                SlotStatus status = status(startAt, endAt, now, remaining, closed);
                slots.add(new SlotView(startAt, endAt, status, status == SlotStatus.AVAILABLE ? remaining : 0));
                cursor = end;
            }
        }
        return slots;
    }

    /**
     * Bookable slots from one Pacific date through another (inclusive), using the current weekly hours and
     * leaving out slots that overlap a closure. Hours and closures are loaded once for the whole range.
     */
    public long openSlots(Amenity amenity, LocalDate from, LocalDate to) {
        List<AmenityWeeklyHour> weekly = hours.findByAmenityIdOrderByOpenTimeAsc(amenity.id);
        if (weekly.isEmpty() || from.isAfter(to)) {
            return 0;
        }
        List<AmenityClosure> closed = closures.findOverlapping(amenity.id,
                from.atStartOfDay(AmenityConfig.ZONE).toInstant(), to.plusDays(1).atStartOfDay(AmenityConfig.ZONE).toInstant());
        long count = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (AmenityWeeklyHour hour : weekly) {
                if (hour.dayOfWeek != date.getDayOfWeek()) continue;
                LocalTime cursor = hour.openTime;
                while (java.time.Duration.between(cursor, hour.closeTime).toMinutes() >= amenity.slotDurationMinutes) {
                    LocalTime end = cursor.plusMinutes(amenity.slotDurationMinutes);
                    Instant startAt = ZonedDateTime.of(date, cursor, AmenityConfig.ZONE).toInstant();
                    Instant endAt = ZonedDateTime.of(date, end, AmenityConfig.ZONE).toInstant();
                    if (closed.stream().noneMatch(c -> startAt.isBefore(c.endAt) && endAt.isAfter(c.startAt))) count++;
                    cursor = end;
                }
            }
        }
        return count;
    }

    public boolean hasAvailableSlot(Amenity amenity, LocalDate date, Instant now) {
        return generate(amenity, date, now).stream().anyMatch(slot -> slot.status() == SlotStatus.AVAILABLE);
    }

    private static SlotStatus status(
            Instant startAt,
            Instant endAt,
            Instant now,
            int remaining,
            List<AmenityClosure> closed
    ) {
        if (!startAt.isAfter(now)) {
            return SlotStatus.PAST;
        }
        if (closed.stream().anyMatch(c -> startAt.isBefore(c.endAt) && endAt.isAfter(c.startAt))) {
            return SlotStatus.CLOSED;
        }
        // Capacity counts households: each booking takes one place, whatever the party size.
        if (remaining == 0) {
            return SlotStatus.BOOKED;
        }
        return SlotStatus.AVAILABLE;
    }

    private static int overlapping(Instant startAt, Instant endAt, List<Reservation> booked) {
        return (int) booked.stream().filter(r -> startAt.isBefore(r.endAt) && endAt.isAfter(r.startAt)).count();
    }
}
