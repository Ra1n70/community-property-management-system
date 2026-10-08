package com.cpms.community.amenity.service;

import com.cpms.community.amenity.AmenityConfig;
import com.cpms.community.amenity.AmenityErrors;
import com.cpms.community.amenity.dto.ReservationDtos.CreateReservation;
import com.cpms.community.amenity.dto.ReservationDtos.ReservationView;
import com.cpms.community.amenity.dto.ReservationDtos.SlotView;
import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.enums.ReservationStatus;
import com.cpms.community.amenity.enums.SlotStatus;
import com.cpms.community.amenity.repository.AmenityRepository;
import com.cpms.community.amenity.repository.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReservationService {
    private static final Duration RESIDENT_CANCEL_WINDOW = Duration.ofHours(2);

    private final ReservationRepository reservations;
    private final AmenityRepository amenities;
    private final SlotGenerator slots;
    private final Clock clock;
    private final com.cpms.community.AccountRepository accounts;

    public ReservationService(
            ReservationRepository reservations,
            AmenityRepository amenities,
            SlotGenerator slots,
            Clock clock,
            com.cpms.community.AccountRepository accounts
    ) {
        this.reservations = reservations;
        this.amenities = amenities;
        this.slots = slots;
        this.clock = clock;
        this.accounts = accounts;
    }

    public List<ReservationView> listMine(String community, Long accountId) {
        Instant now = Instant.now(clock);
        return views(reservations.findByCommunityAndAccountIdOrderByStartAtDesc(community.trim(), accountId), now);
    }

    public List<ReservationView> listManaged(String community, Long amenityId, LocalDate date) {
        Instant now = Instant.now(clock);
        ZonedDateTime dayStart = date.atStartOfDay(AmenityConfig.ZONE);
        Instant start = dayStart.toInstant();
        Instant end = dayStart.plusDays(1).toInstant();
        List<Reservation> found = amenityId == null
                ? reservations.findByCommunityAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                        community.trim(), start, end)
                : reservations.findByCommunityAndAmenityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                        community.trim(), amenityId, start, end);
        return views(found, now);
    }

    /** Looks up all amenity names in one query instead of one per reservation. */
    private List<ReservationView> views(List<Reservation> found, Instant now) {
        Map<Long, String> names = amenities.findAllById(found.stream().map(reservation -> reservation.amenityId).distinct().toList())
                .stream().collect(Collectors.toMap(amenity -> amenity.id, amenity -> amenity.name));
        return found.stream()
                .map(reservation -> ReservationView.of(reservation, names.getOrDefault(reservation.amenityId, "Unknown amenity"), now))
                .toList();
    }

    @Transactional
    public ReservationView create(CreateReservation input, Long accountId) {
        var resident=accounts.lockById(accountId).orElseThrow(AmenityErrors::reservationNotFound);
        if(!resident.community.equals(input.community())||resident.role!=com.cpms.community.Account.Role.RESIDENT
            ||resident.status!=com.cpms.community.Account.Status.APPROVED)
            throw AmenityErrors.fail(HttpStatus.FORBIDDEN,"Approved resident access required.");
        input=new CreateReservation(resident.community,resident.room,resident.name,input.amenityId(),input.startAt());
        Amenity amenity = amenities.lockByIdAndCommunity(input.amenityId(), input.community().trim())
                .orElseThrow(AmenityErrors::notFound);
        Instant now = Instant.now(clock);
        Instant startAt = input.startAt();
        LocalDate localDate = startAt.atZone(AmenityConfig.ZONE).toLocalDate();
        LocalDate today = LocalDate.now(clock);
        if (localDate.isBefore(today)) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Reservations cannot be made in the past.");
        }
        if (localDate.isAfter(today.plusDays(amenity.maxAdvanceDays))) {
            throw AmenityErrors.fail(
                    HttpStatus.BAD_REQUEST,
                    "Reservations cannot be made more than " + amenity.maxAdvanceDays + " days in advance."
            );
        }
        SlotView slot = slots.generate(amenity, localDate, now).stream()
                .filter(candidate -> candidate.startAt().equals(startAt))
                .findFirst()
                .orElseThrow(() -> AmenityErrors.fail(HttpStatus.BAD_REQUEST, "That time is outside opening hours."));
        if (slot.status() == SlotStatus.PAST) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "That time slot has already started.");
        }
        if (slot.status() == SlotStatus.CLOSED) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "That time slot is closed.");
        }
        if (slot.status() == SlotStatus.BOOKED) {
            throw AmenityErrors.fail(HttpStatus.CONFLICT, "This time slot is no longer available.");
        }
        List<Reservation> sameSlot = reservations.findByAmenityIdAndStatusAndStartAtLessThanAndEndAtGreaterThan(
                amenity.id, ReservationStatus.UPCOMING, slot.endAt(), slot.startAt());
        String room = input.room().trim();
        if (sameSlot.stream().anyMatch(r -> accountId.equals(r.accountId) || room.equals(r.room))) {
            throw AmenityErrors.fail(HttpStatus.CONFLICT, "Your household already booked this time slot.");
        }
        ZonedDateTime dayStart = localDate.atStartOfDay(AmenityConfig.ZONE);
        long used = reservations.findByAmenityIdAndRoomAndStatusNotAndStartAtGreaterThanEqualAndStartAtLessThan(
                amenity.id,
                input.room().trim(),
                ReservationStatus.CANCELLED,
                dayStart.toInstant(),
                dayStart.plusDays(1).toInstant()
        ).size();
        if (used >= amenity.maxSlotsPerDay) {
            throw AmenityErrors.fail(
                    HttpStatus.BAD_REQUEST,
                    "This household already booked the daily limit for this amenity."
            );
        }
        Reservation reservation = new Reservation();
        reservation.amenityId = amenity.id;
        reservation.accountId = accountId;
        reservation.community = amenity.community;
        reservation.room = room;
        reservation.guestName = input.guestName().trim();
        reservation.startAt = slot.startAt();
        reservation.endAt = slot.endAt();
        reservation.status = ReservationStatus.UPCOMING;
        reservation.fee = amenity.chargeable ? amenity.fee : java.math.BigDecimal.ZERO.setScale(2);
        reservation.slotKey = slotKey(amenity, slot.startAt(), sameSlot);
        reservation.createdAt = now;
        reservations.saveAndFlush(reservation);
        return ReservationView.of(reservation, amenity.name, now);
    }

    @Transactional
    public ReservationView cancelByResident(Long id, String community, Long accountId) {
        Reservation reservation = reservations.findByIdAndCommunityAndAccountId(id, community, accountId)
                .orElseThrow(AmenityErrors::reservationNotFound);
        Instant now = Instant.now(clock);
        ensureUpcoming(reservation, now);
        if (!now.isBefore(reservation.startAt.minus(RESIDENT_CANCEL_WINDOW))) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Reservations cannot be cancelled within two hours of start time.");
        }
        return cancel(reservation, "resident", null, now);
    }

    @Transactional
    public ReservationView cancelByManager(Long id, String community, String reason) {
        Reservation reservation = reservations.findByIdAndCommunity(id, community.trim())
                .orElseThrow(AmenityErrors::reservationNotFound);
        Instant now = Instant.now(clock);
        ensureUpcoming(reservation, now);
        return cancel(reservation, "manager", reason == null || reason.isBlank() ? null : reason.trim(), now);
    }

    private ReservationView cancel(Reservation reservation, String actor, String reason, Instant now) {
        reservation.status = ReservationStatus.CANCELLED;
        reservation.slotKey = null;
        reservation.cancelReason = reason;
        reservation.cancelledBy = actor;
        reservation.cancelledAt = now;
        return ReservationView.of(reservation, amenityName(reservation.amenityId), now);
    }

    /**
     * One key per place in a slot ("amenityId:startAt:place", place 1..capacity), so the unique constraint
     * still stops two bookings from taking the same place. Keys without a place number come from before
     * capacity was enforced and hold place 1.
     */
    private static String slotKey(Amenity amenity, Instant startAt, List<Reservation> sameSlot) {
        String prefix = amenity.id + ":" + startAt;
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (Reservation r : sameSlot) {
            if (r.slotKey == null) continue;
            if (r.slotKey.equals(prefix)) taken.add(1);
            else if (r.slotKey.startsWith(prefix + ":")) {
                try { taken.add(Integer.parseInt(r.slotKey.substring(prefix.length() + 1))); }
                catch (NumberFormatException ignored) { }
            }
        }
        int place = 1;
        while (taken.contains(place)) place++;
        return prefix + ":" + place;
    }

    private static void ensureUpcoming(Reservation reservation, Instant now) {
        if (reservation.status == ReservationStatus.CANCELLED) {
            throw AmenityErrors.fail(HttpStatus.CONFLICT, "This reservation is already cancelled.");
        }
        if (!reservation.endAt.isAfter(now)) {
            throw AmenityErrors.fail(HttpStatus.CONFLICT, "Completed reservations cannot be cancelled.");
        }
    }

    private String amenityName(Long amenityId) {
        return amenities.findById(amenityId).map(amenity -> amenity.name).orElse("Unknown amenity");
    }
}
