package com.cpms.community.locker.controller;

import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.IntakeSource;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.service.ParcelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.cpms.community.locker.service.ManagerParcelService;
import java.util.List;
import java.time.Instant;

@RestController
@RequestMapping("/api/manager/parcels")
public class ParcelApi {

    public record PropertyIntakeRequest(
            @NotNull
            Long residentId,

            @NotNull
            Long cellId,

            @NotBlank
            @Size(max = 100)
            String carrierName,

            @Size(max = 100)
            String trackingNumber,

            @NotNull
            CellSize packageSize
    ) {}

    public record ParcelView(
            Long id,
            Long residentId,
            String residentName,
            String room,
            Long lockerId,
            String lockerNumber,
            String lockerLocation,
            Long cellId,
            String cellNumber,
            String carrierName,
            String trackingNumber,
            CellSize packageSize,
            ParcelStatus status,
            IntakeSource intakeSource,
            Long registeredBy,
            String courierName,
            Instant storedAt,
            Instant expiresAt
    ) {
        public static ParcelView of(Parcel parcel, Locker locker) {
            return new ParcelView(
                    parcel.id,
                    parcel.resident.id,
                    parcel.resident.name,
                    parcel.resident.room,
                    locker.id,
                    locker.lockerNumber,
                    locker.location,
                    parcel.cell.id,
                    parcel.cell.cellNumber,
                    parcel.carrierName,
                    parcel.trackingNumber,
                    parcel.packageSize,
                    parcel.status,
                    parcel.intakeSource,
                    parcel.registeredBy,
                    parcel.courierName,
                    parcel.storedAt,
                    parcel.expiresAt
            );
        }
    }

    public record IntakeResponse(
            ParcelView parcel,
            String pickupCode
    ) {
        public static IntakeResponse of(
                ParcelService.IntakeResult result
        ) {
            return new IntakeResponse(
                    ParcelView.of(result.parcel(), result.locker()),
                    result.pickupCode()
            );
        }
    }

    private final ParcelService service;
    private final ManagerParcelService managerParcelService;

    public ParcelApi(
            ParcelService service,
            ManagerParcelService managerParcelService
    ) {
        this.service = service;
        this.managerParcelService = managerParcelService;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IntakeResponse propertyIntake(
            Authentication auth,
            @Valid @RequestBody
            PropertyIntakeRequest input
    ) {
        return IntakeResponse.of(
                service.propertyIntake(
                        auth.getName(),
                        input.residentId(),
                        input.cellId(),
                        input.carrierName(),
                        input.trackingNumber(),
                        input.packageSize()
                )
        );
    }
    @GetMapping
    public List<ManagerParcelService.ParcelView> list(
            Authentication auth,
            @RequestParam(required = false) ParcelStatus status,
            @RequestParam(required = false) Long lockerId
    ) {
        return managerParcelService.list(
                auth.getName(),
                status,
                lockerId
        );
    }
    /** Paged list used by the Packages page. */
    @GetMapping("/page")
    public com.cpms.community.PageView<ManagerParcelService.ParcelView> page(
            Authentication auth,
            @RequestParam(required = false) ParcelStatus status,
            @RequestParam(required = false) Long lockerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return managerParcelService.page(auth.getName(), status, lockerId, page, size);
    }
    @GetMapping("/{parcelId}")
    public ManagerParcelService.ParcelView get(
            Authentication auth,
            @PathVariable Long parcelId
    ) {
        return managerParcelService.get(auth.getName(), parcelId);
    }
    @PostMapping("/{parcelId}/retrieve")
    public ManagerParcelService.ParcelView retrieveExpired(
            Authentication auth,
            @PathVariable Long parcelId
    ) {
        return managerParcelService.retrieveExpired(
                auth.getName(),
                parcelId
        );
    }
    public record QueuedResponse(String status) {}
    @PostMapping("/{parcelId}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QueuedResponse resend(
            Authentication auth,
            @PathVariable Long parcelId
    ) {
        managerParcelService.resendPickupEmail(
                auth.getName(), parcelId
        );
        return new QueuedResponse("EMAIL_QUEUED");
    }

    @PostMapping("/{parcelId}/regenerate-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QueuedResponse regenerateCode(
            Authentication auth,
            @PathVariable Long parcelId
    ) {
        managerParcelService.regeneratePickupCode(
                auth.getName(), parcelId
        );
        return new QueuedResponse("PICKUP_CODE_UPDATED");
    }
    @GetMapping("/residents")
    public List<ManagerParcelService.ResidentOption> searchResidents(
            Authentication auth,
            @RequestParam String query
    ) {
        return managerParcelService.searchResidents(
                auth.getName(), query
        );
    }
}
