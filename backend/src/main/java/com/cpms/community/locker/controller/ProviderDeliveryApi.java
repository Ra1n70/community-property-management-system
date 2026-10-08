package com.cpms.community.locker.controller;

import com.cpms.community.locker.service.CourierService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Courier management. Delivery companies manage their own couriers' kiosk store codes
 * (/api/provider/deliveries); the property manager manages independent couriers (/api/manager/couriers).
 */
@RestController
public class ProviderDeliveryApi {
    public record Active(boolean active) {}

    private final CourierService couriers;

    public ProviderDeliveryApi(CourierService couriers) {
        this.couriers = couriers;
    }

    @GetMapping({"/api/provider/deliveries/couriers", "/api/manager/couriers"})
    public List<CourierService.CourierView> list(Authentication auth) {
        return couriers.list(auth.getName());
    }

    @PostMapping({"/api/provider/deliveries/couriers", "/api/manager/couriers"})
    @ResponseStatus(HttpStatus.CREATED)
    public CourierService.IssuedCourier create(Authentication auth, @Valid @RequestBody CourierService.Input input) {
        return couriers.create(auth.getName(), input);
    }

    @PutMapping({"/api/provider/deliveries/couriers/{id}", "/api/manager/couriers/{id}"})
    public CourierService.CourierView update(Authentication auth, @PathVariable Long id,
                                             @Valid @RequestBody CourierService.Update input) {
        return couriers.update(auth.getName(), id, input);
    }

    @PostMapping({"/api/provider/deliveries/couriers/{id}/store-code", "/api/manager/couriers/{id}/store-code"})
    public CourierService.IssuedCourier resetCode(Authentication auth, @PathVariable Long id) {
        return couriers.resetCode(auth.getName(), id);
    }

    @PutMapping({"/api/provider/deliveries/couriers/{id}/active", "/api/manager/couriers/{id}/active"})
    public CourierService.CourierView setActive(Authentication auth, @PathVariable Long id, @RequestBody Active input) {
        return couriers.setActive(auth.getName(), id, input.active());
    }

    @GetMapping("/api/provider/deliveries/parcels")
    public List<CourierService.DeliveryView> deliveries(Authentication auth) {
        return couriers.deliveries(auth.getName());
    }

    @GetMapping("/api/provider/deliveries/parcels/page")
    public com.cpms.community.PageView<CourierService.DeliveryView> deliveriesPage(
            Authentication auth, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return couriers.deliveries(auth.getName(), page, size);
    }
}
