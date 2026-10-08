package com.cpms.community.locker.controller;

import com.cpms.community.locker.service.ResidentParcelService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resident/parcels")
public class ResidentParcelApi {

    private final ResidentParcelService service;

    public ResidentParcelApi(ResidentParcelService service) {
        this.service = service;
    }

    @GetMapping
    public List<ResidentParcelService.ParcelView> listMine(
            Authentication auth
    ) {
        return service.listMine(auth.getName());
    }

    @GetMapping("/page")
    public com.cpms.community.PageView<ResidentParcelService.ParcelView> pageMine(
            Authentication auth,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return service.pageMine(auth.getName(), page, size);
    }

    @GetMapping("/{parcelId}")
    public ResidentParcelService.ParcelDetailView getMine(
            Authentication auth,
            @PathVariable Long parcelId
    ) {
        return service.getMine(auth.getName(), parcelId);
    }
}
