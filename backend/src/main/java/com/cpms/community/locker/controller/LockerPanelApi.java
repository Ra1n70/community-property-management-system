package com.cpms.community.locker.controller;

import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.Courier;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.LockerStatus;
import com.cpms.community.locker.repository.LockerRepository;
import com.cpms.community.locker.service.CarrierIntakeService;
import com.cpms.community.locker.service.CarrierRecipientService;
import com.cpms.community.locker.service.CourierService;
import com.cpms.community.locker.service.PickupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

/**
 * Kiosk endpoints. Residents pick up with a pickup code; couriers store packages with their
 * personal store code, which identifies the courier and their delivery company. No login is used.
 */
@RestController
@RequestMapping("/api/locker-panel")
public class LockerPanelApi {
    public static final String INDEPENDENT_CARRIER = "Independent courier";

    public record PickupRequest(String pickupCode) {}
    public record LocationOption(Long id, String location, String community) {}
    public record StoreCode(@NotBlank @Size(max = 20) String storeCode) {}
    public record RoomLookup(@NotBlank @Size(max = 20) String storeCode, @NotBlank @Size(max = 30) String room) {}
    public record BeginIntakeRequest(
            @NotBlank @Size(max = 20) String storeCode,
            @NotBlank @Size(max = 30) String room,
            @NotNull Long residentId,
            @NotNull CellSize packageSize
    ) {}
    public record ConfirmIntakeRequest(
            @NotBlank @Size(max = 20) String storeCode,
            @NotBlank @Size(max = 128) String sessionToken
    ) {}
    public record ConfirmIntakeResponse(String status, String cellNumber, String lockerLocation) {}

    private final PickupService pickupService;
    private final CarrierRecipientService recipientService;
    private final CarrierIntakeService carrierIntakeService;
    private final CourierService couriers;
    private final LockerRepository lockers;

    public LockerPanelApi(PickupService pickupService, CarrierRecipientService recipientService,
                          CarrierIntakeService carrierIntakeService, CourierService couriers, LockerRepository lockers) {
        this.pickupService = pickupService;
        this.recipientService = recipientService;
        this.carrierIntakeService = carrierIntakeService;
        this.couriers = couriers;
        this.lockers = lockers;
    }

    @GetMapping("/locations")
    public List<LocationOption> locations() {
        return lockers.findAll().stream().filter(l -> l.status == LockerStatus.ACTIVE)
                .sorted(Comparator.comparing(l -> l.location))
                .map(l -> new LocationOption(l.id, l.location, l.community)).toList();
    }

    @PostMapping("/{lockerId}/pickup")
    public PickupService.PickupResult pickup(@PathVariable Long lockerId, @RequestBody PickupRequest input) {
        return pickupService.pickup(lockerId, input.pickupCode());
    }

    private Courier courier(Long lockerId, String storeCode, HttpServletRequest request) {
        Locker locker = lockers.findById(lockerId)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Locker not found."));
        return couriers.authenticate(storeCode, locker, request.getRemoteAddr());
    }

    @PostMapping("/{lockerId}/courier/verify")
    public CourierService.KioskCourier verify(@PathVariable Long lockerId, @Valid @RequestBody StoreCode input,
                                              HttpServletRequest request) {
        return CourierService.kiosk(courier(lockerId, input.storeCode(), request));
    }

    @PostMapping("/{lockerId}/courier/residents")
    public List<CarrierRecipientService.Recipient> findResidents(@PathVariable Long lockerId,
                                                                 @Valid @RequestBody RoomLookup input,
                                                                 HttpServletRequest request) {
        courier(lockerId, input.storeCode(), request);
        return recipientService.lookup(lockerId, input.room());
    }

    @PostMapping("/{lockerId}/intake/start")
    @ResponseStatus(HttpStatus.CREATED)
    public CarrierIntakeService.BeginResult beginIntake(@PathVariable Long lockerId,
                                                        @Valid @RequestBody BeginIntakeRequest input,
                                                        HttpServletRequest request) {
        Courier c = courier(lockerId, input.storeCode(), request);
        return carrierIntakeService.begin(lockerId, input.room(), input.residentId(),
                c.companyName == null ? INDEPENDENT_CARRIER : c.companyName, input.packageSize(), c);
    }

    @PostMapping("/{lockerId}/intake/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfirmIntakeResponse confirmIntake(@PathVariable Long lockerId,
                                               @Valid @RequestBody ConfirmIntakeRequest input,
                                               HttpServletRequest request) {
        Courier c = courier(lockerId, input.storeCode(), request);
        CarrierIntakeService.ConfirmResult result = carrierIntakeService.confirm(lockerId, input.sessionToken(), c.id);
        return new ConfirmIntakeResponse("STORED", result.cellNumber(), result.lockerLocation());
    }
}
