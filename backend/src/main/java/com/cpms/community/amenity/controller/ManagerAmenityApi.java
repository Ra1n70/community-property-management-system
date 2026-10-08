package com.cpms.community.amenity.controller;

import com.cpms.community.amenity.dto.AmenityDtos.AmenityInput;
import com.cpms.community.amenity.dto.AmenityDtos.AmenityView;
import com.cpms.community.amenity.dto.AmenityDtos.ClosureView;
import com.cpms.community.amenity.dto.AmenityDtos.ReplaceHours;
import com.cpms.community.amenity.dto.ReservationDtos.CancelReservation;
import com.cpms.community.amenity.dto.ReservationDtos.ReservationView;
import com.cpms.community.amenity.service.AmenityService;
import com.cpms.community.amenity.service.ReservationService;
import jakarta.validation.Valid;
import com.cpms.community.Account;
import com.cpms.community.amenity.AmenityAccess;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/manager")
public class ManagerAmenityApi {
    private final AmenityAccess access;
    private String community(Authentication auth) {return access.require(auth,Account.Role.MANAGER).community;}
    private final AmenityService amenities;
    private final ReservationService reservations;

    public ManagerAmenityApi(AmenityService amenities, ReservationService reservations, AmenityAccess access) {
        this.access=access;
        this.amenities = amenities;
        this.reservations = reservations;
    }

    @GetMapping("/amenities")
    public List<AmenityView> list(Authentication auth) {
        return amenities.list(community(auth));
    }

    @PostMapping("/amenities")
    @ResponseStatus(HttpStatus.CREATED)
    public AmenityView create(Authentication auth, @Valid @RequestBody AmenityInput input) {
        return amenities.create(community(auth), input);
    }

    @PutMapping("/amenities/{id}")
    public AmenityView update(Authentication auth, @PathVariable Long id, @Valid @RequestBody AmenityInput input) {
        return amenities.update(id, community(auth), input);
    }

    @PutMapping("/amenities/{id}/hours")
    public AmenityView hours(Authentication auth, @PathVariable Long id, @Valid @RequestBody ReplaceHours input) {
        return amenities.replaceHours(id, community(auth), input);
    }

    public record ClosureInput(@jakarta.validation.constraints.NotNull java.time.LocalDateTime startAt,
                               @jakarta.validation.constraints.NotNull java.time.LocalDateTime endAt,
                               @jakarta.validation.constraints.Size(max=1000) String reason) {}
    @PostMapping("/amenities/{id}/closures")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ReservationView> close(Authentication auth, @PathVariable Long id, @Valid @RequestBody ClosureInput input) {
        return amenities.close(id, community(auth), input.startAt().atZone(com.cpms.community.amenity.AmenityConfig.ZONE).toInstant(), input.endAt().atZone(com.cpms.community.amenity.AmenityConfig.ZONE).toInstant(), input.reason());
    }

    @GetMapping("/amenities/{id}/closures")
    public List<ClosureView> closures(Authentication auth, @PathVariable Long id) {
        return amenities.closures(id, community(auth));
    }

    @DeleteMapping("/amenities/{id}/closures/{closureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClosure(Authentication auth, @PathVariable Long id, @PathVariable Long closureId) {
        amenities.deleteClosure(id, closureId, community(auth));
    }

    @PostMapping("/amenities/{id}/image")
    public AmenityView upload(
            @PathVariable Long id,
            Authentication auth,
            @RequestParam("file") MultipartFile file
    ) {
        return amenities.saveImage(id, community(auth), file);
    }

    @DeleteMapping("/amenities/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable Long id, Authentication auth) {
        amenities.deleteImage(id, community(auth));
    }

    @GetMapping("/reservations")
    public List<ReservationView> listReservations(
            Authentication auth,
            @RequestParam(required = false) Long amenityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return reservations.listManaged(community(auth), amenityId, date);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(Authentication auth, @PathVariable Long id, @Valid @RequestBody CancelReservation input) {
        return reservations.cancelByManager(id, community(auth), input.reason());
    }
    @GetMapping("/amenities/{id}/hours")
    public List<com.cpms.community.amenity.dto.AmenityDtos.HourRange> hours(Authentication auth, @PathVariable Long id) {
        return amenities.getHours(id,community(auth));
    }
    @GetMapping("/amenities/{id}/image")
    public org.springframework.http.ResponseEntity<byte[]> image(Authentication auth, @PathVariable Long id) {
        String community=community(auth);
        var amenity=amenities.require(id,community);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(
            amenity.imageContentType==null?"application/octet-stream":amenity.imageContentType)).body(amenities.imageBytes(amenity));
    }
}
