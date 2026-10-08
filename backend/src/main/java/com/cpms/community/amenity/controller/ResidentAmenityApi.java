package com.cpms.community.amenity.controller;

import com.cpms.community.amenity.dto.AmenityDtos.AmenityView;
import com.cpms.community.amenity.dto.ReservationDtos.CreateReservation;
import com.cpms.community.amenity.dto.ReservationDtos.ReservationView;
import com.cpms.community.amenity.dto.ReservationDtos.SlotView;
import com.cpms.community.amenity.service.AmenityService;
import com.cpms.community.amenity.service.ReservationService;
import jakarta.validation.Valid;
import com.cpms.community.Account;
import com.cpms.community.amenity.AmenityAccess;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/resident")
public class ResidentAmenityApi {
    private final AmenityAccess access;
    private final AmenityService amenities;
    private final ReservationService reservations;

    public ResidentAmenityApi(AmenityService amenities, ReservationService reservations, AmenityAccess access) {
        this.access=access;
        this.amenities = amenities;
        this.reservations = reservations;
    }

    @GetMapping("/amenities")
    public List<AmenityView> list(Authentication auth) {
        return amenities.list(access.require(auth, Account.Role.RESIDENT).community);
    }

    @GetMapping("/amenities/{id}")
    public AmenityView get(@PathVariable Long id, Authentication auth) {
        return amenities.get(id, access.require(auth, Account.Role.RESIDENT).community);
    }

    @GetMapping("/amenities/{id}/slots")
    public List<SlotView> slots(
            @PathVariable Long id,
            Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return amenities.slots(id, access.require(auth, Account.Role.RESIDENT).community, date);
    }

    @GetMapping("/amenities/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id, Authentication auth) {
        String community=access.require(auth, Account.Role.RESIDENT).community;
        var amenity = amenities.require(id, community);
        byte[] body = amenities.imageBytes(amenity);
        String contentType = amenity.imageContentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : amenity.imageContentType;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(body);
    }

    public record Booking(@jakarta.validation.constraints.NotNull Long amenityId,
                          @jakarta.validation.constraints.NotNull java.time.Instant startAt) {}
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationView create(Authentication auth, @Valid @RequestBody Booking input) {
        Account a=access.require(auth,Account.Role.RESIDENT);
        return reservations.create(new CreateReservation(a.community,a.room,a.name,input.amenityId(),input.startAt()),a.id);
    }
    @GetMapping("/reservations")
    public List<ReservationView> mine(Authentication auth) {
        Account a=access.require(auth,Account.Role.RESIDENT);
        return reservations.listMine(a.community,a.id);
    }
    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(Authentication auth,@PathVariable Long id) {
        Account a=access.require(auth,Account.Role.RESIDENT);
        return reservations.cancelByResident(id,a.community,a.id);
    }
}
