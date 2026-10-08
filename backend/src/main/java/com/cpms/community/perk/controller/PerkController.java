package com.cpms.community.perk.controller;

import com.cpms.community.perk.model.PerkDto;
import com.cpms.community.perk.service.PerkService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/perks")
public class PerkController {
    private final PerkService perks;

    public PerkController(PerkService perks) {
        this.perks = perks;
    }

    @GetMapping
    public List<PerkDto.Summary> list(Authentication authentication,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false, defaultValue = "active") String status,
                                      @RequestParam(required = false, defaultValue = "new") String sort) {
        return perks.list(authentication.getName(), category, status, sort);
    }

    @GetMapping("/{id}")
    public PerkDto.Detail detail(Authentication authentication, @PathVariable Long id) {
        return perks.detail(authentication.getName(), id);
    }
}
