package com.cpms.community.amenity.service;

import com.cpms.community.amenity.AmenityConfig;
import com.cpms.community.amenity.AmenityErrors;
import com.cpms.community.amenity.dto.AmenityDtos.AmenityInput;
import com.cpms.community.amenity.dto.AmenityDtos.AmenityView;
import com.cpms.community.amenity.dto.AmenityDtos.ClosureView;
import com.cpms.community.amenity.dto.AmenityDtos.HourRange;
import com.cpms.community.amenity.dto.AmenityDtos.ReplaceHours;
import com.cpms.community.amenity.dto.ReservationDtos.ReservationView;
import com.cpms.community.amenity.dto.ReservationDtos.SlotView;
import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.amenity.entity.AmenityClosure;
import com.cpms.community.amenity.entity.AmenityWeeklyHour;
import com.cpms.community.amenity.entity.Reservation;
import com.cpms.community.amenity.enums.ReservationStatus;
import com.cpms.community.amenity.repository.AmenityClosureRepository;
import com.cpms.community.amenity.repository.AmenityRepository;
import com.cpms.community.amenity.repository.AmenityWeeklyHourRepository;
import com.cpms.community.amenity.repository.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class AmenityService {
    private final AmenityRepository amenities;
    private final AmenityWeeklyHourRepository hours;
    private final AmenityClosureRepository closures;
    private final ReservationRepository reservations;
    private final SlotGenerator slots;
    private final ImageStorage images;
    private final Clock clock;

    public AmenityService(
            AmenityRepository amenities,
            AmenityWeeklyHourRepository hours,
            AmenityClosureRepository closures,
            ReservationRepository reservations,
            SlotGenerator slots,
            ImageStorage images,
            Clock clock
    ) {
        this.amenities = amenities;
        this.hours = hours;
        this.closures = closures;
        this.reservations = reservations;
        this.slots = slots;
        this.images = images;
        this.clock = clock;
    }

    public List<AmenityView> list(String community) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        return amenities.findByCommunityOrderByNameAsc(community.trim()).stream()
                .map(amenity -> toView(amenity, slots.hasAvailableSlot(amenity, today, now)))
                .toList();
    }

    public AmenityView get(Long id, String community) {
        Amenity amenity = require(id, community);
        Instant now = Instant.now(clock);
        return toView(amenity, slots.hasAvailableSlot(amenity, LocalDate.now(clock), now));
    }

    public List<SlotView> slots(Long id, String community, LocalDate date) {
        Amenity amenity = require(id, community);
        return slots.generate(amenity, date, Instant.now(clock));
    }

    @Transactional
    public AmenityView create(String community, AmenityInput input) {
        Amenity amenity = new Amenity();
        apply(
                amenity,
                community,
                input.name(),
                input.type(),
                input.location(),
                input.capacity(),
                input.slotDurationMinutes(),
                input.chargeable(),
                input.fee(),
                input.description(),
                input.maxSlotsPerDay(),
                input.maxAdvanceDays()
        );
        Instant now = Instant.now(clock);
        amenity.createdAt = now;
        amenity.updatedAt = now;
        amenities.save(amenity);
        return toView(amenity, false);
    }

    @Transactional
    public AmenityView update(Long id, String community, AmenityInput input) {
        Amenity amenity = amenities.lockByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
        apply(
                amenity,
                community,
                input.name(),
                input.type(),
                input.location(),
                input.capacity(),
                input.slotDurationMinutes(),
                input.chargeable(),
                input.fee(),
                input.description(),
                input.maxSlotsPerDay(),
                input.maxAdvanceDays()
        );
        amenity.updatedAt = Instant.now(clock);
        return toView(amenity, slots.hasAvailableSlot(amenity, LocalDate.now(clock), Instant.now(clock)));
    }

    @Transactional
    public AmenityView replaceHours(Long id, String community, ReplaceHours input) {
        Amenity amenity = amenities.lockByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
        for (HourRange hour : input.hours()) {
            if (!hour.openTime().isBefore(hour.closeTime())) {
                throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Open time must be before close time.");
            }
        }
        for (int i=0;i<input.hours().size();i++) {
            var a=input.hours().get(i);
            for(int j=i+1;j<input.hours().size();j++) {
                var b=input.hours().get(j);
                if(a.dayOfWeek()==b.dayOfWeek() && a.openTime().isBefore(b.closeTime()) && a.closeTime().isAfter(b.openTime()))
                    throw AmenityErrors.fail(HttpStatus.BAD_REQUEST,"Opening hours on the same day cannot overlap.");
            }
        }
        // Like closures, new hours never cancel bookings; the manager cancels them first.
        Instant now = Instant.now(clock);
        long outside = reservations.findByAmenityIdAndStatusAndEndAtGreaterThan(amenity.id, ReservationStatus.UPCOMING, now)
                .stream().filter(reservation -> reservation.startAt.isAfter(now) && !withinHours(reservation, input.hours())).count();
        if (outside > 0) {
            throw AmenityErrors.fail(HttpStatus.CONFLICT, outside == 1
                    ? "1 upcoming reservation falls outside these hours. Cancel it first or keep that time open."
                    : outside + " upcoming reservations fall outside these hours. Cancel them first or keep those times open.");
        }
        hours.deleteByAmenityId(amenity.id);
        hours.flush();
        hours.saveAll(input.hours().stream()
                .map(hour -> new AmenityWeeklyHour(amenity.id, hour.dayOfWeek(), hour.openTime(), hour.closeTime()))
                .toList());
        amenity.updatedAt = Instant.now(clock);
        return toView(amenity, slots.hasAvailableSlot(amenity, LocalDate.now(clock), Instant.now(clock)));
    }

    @Transactional
    public List<ReservationView> close(Long id, String community, Instant startAt, Instant endAt, String reason) {
        Amenity amenity = amenities.lockByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
        if (!startAt.isBefore(endAt)) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Closure start must be before end.");
        }
        AmenityClosure closure = new AmenityClosure();
        closure.amenityId = amenity.id;
        closure.startAt = startAt;
        closure.endAt = endAt;
        closure.reason = reason == null ? "" : reason.trim();
        closure.createdBy = "manager";
        closure.createdAt = Instant.now(clock);
        closures.save(closure);
        Instant now = Instant.now(clock);
        return reservations.findByAmenityIdAndStatusAndStartAtLessThanAndEndAtGreaterThan(
                        amenity.id,
                        ReservationStatus.UPCOMING,
                        endAt,
                        startAt
                ).stream()
                .map(reservation -> ReservationView.of(reservation, amenity.name, now))
                .toList();
    }

    /** Closures that have not ended yet, earliest first. */
    public List<ClosureView> closures(Long id, String community) {
        Amenity amenity = require(id, community);
        return closures.findByAmenityIdAndEndAtGreaterThanOrderByStartAtAsc(amenity.id, Instant.now(clock)).stream()
                .map(closure -> new ClosureView(closure.id, closure.startAt, closure.endAt, closure.reason))
                .toList();
    }

    @Transactional
    public void deleteClosure(Long id, Long closureId, String community) {
        Amenity amenity = amenities.lockByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
        closures.delete(closures.findByIdAndAmenityId(closureId, amenity.id)
                .orElseThrow(() -> AmenityErrors.fail(HttpStatus.NOT_FOUND, "Closure not found.")));
    }

    @Transactional
    public AmenityView saveImage(Long id, String community, MultipartFile file) {
        Amenity amenity = amenities.lockByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
        images.save(amenity, file);
        amenity.updatedAt = Instant.now(clock);
        return toView(amenity, slots.hasAvailableSlot(amenity, LocalDate.now(clock), Instant.now(clock)));
    }

    @Transactional
    public void deleteImage(Long id, String community) {
        Amenity amenity = amenities.lockByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
        images.delete(amenity);
        amenity.updatedAt = Instant.now(clock);
    }

    public byte[] imageBytes(Amenity amenity) {
        return images.read(amenity);
    }

    public List<HourRange> getHours(Long id,String community) {
        require(id,community);
        return hours.findByAmenityIdOrderByOpenTimeAsc(id).stream()
            .map(h -> new HourRange(h.dayOfWeek,h.openTime,h.closeTime)).toList();
    }

    public Amenity require(Long id, String community) {
        return amenities.findByIdAndCommunity(id, community.trim()).orElseThrow(AmenityErrors::notFound);
    }

    private static boolean withinHours(Reservation reservation, List<HourRange> ranges) {
        ZonedDateTime start = reservation.startAt.atZone(AmenityConfig.ZONE);
        ZonedDateTime end = reservation.endAt.atZone(AmenityConfig.ZONE);
        return start.toLocalDate().equals(end.toLocalDate()) && ranges.stream().anyMatch(range ->
                range.dayOfWeek() == start.getDayOfWeek()
                        && !start.toLocalTime().isBefore(range.openTime())
                        && !end.toLocalTime().isAfter(range.closeTime()));
    }

    private AmenityView toView(Amenity amenity, boolean hasAvailableSlotsToday) {
        return AmenityView.of(amenity, images.publicUrl(amenity), hasAvailableSlotsToday);
    }

    private static void apply(
            Amenity amenity,
            String community,
            String name,
            String type,
            String location,
            int capacity,
            int slotDurationMinutes,
            boolean chargeable,
            BigDecimal fee,
            String description,
            Integer maxSlotsPerDay,
            Integer maxAdvanceDays
    ) {
        amenity.community = community.trim();
        amenity.name = name.trim();
        amenity.type = type.trim();
        amenity.location = location.trim();
        amenity.capacity = capacity;
        amenity.slotDurationMinutes = slotDurationMinutes;
        amenity.chargeable = chargeable;
        // Validation allows at most two decimal places, so setScale(2) never rounds.
        amenity.fee = chargeable && fee != null ? fee.setScale(2) : BigDecimal.ZERO.setScale(2);
        amenity.description = description == null || description.isBlank() ? null : description.trim();
        amenity.maxSlotsPerDay = maxSlotsPerDay == null ? 2 : maxSlotsPerDay;
        amenity.maxAdvanceDays = maxAdvanceDays == null ? 7 : maxAdvanceDays;
    }
}
