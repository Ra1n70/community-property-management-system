package com.cpms.community.perk.controller;

import com.cpms.community.perk.model.PerkDto;
import com.cpms.community.perk.service.PerkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/manager/perks")
public class ManagerPerkApi {
    private final PerkService perks;

    public ManagerPerkApi(PerkService perks) {
        this.perks = perks;
    }

    @GetMapping
    public List<PerkDto.Summary> list(Authentication authentication,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false, defaultValue = "all") String status,
                                      @RequestParam(required = false, defaultValue = "new") String sort) {
        return perks.managerList(authentication.getName(), category, status, sort);
    }

    @GetMapping("/{id}")
    public PerkDto.Detail detail(Authentication authentication, @PathVariable Long id) {
        return perks.managerDetail(authentication.getName(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PerkDto.Detail create(Authentication authentication,
                                 @Valid @RequestBody PerkDto.SaveRequest request) {
        return perks.create(authentication.getName(), request);
    }

    @PutMapping("/{id}")
    public PerkDto.Detail update(Authentication authentication, @PathVariable Long id,
                                 @Valid @RequestBody PerkDto.SaveRequest request) {
        return perks.update(authentication.getName(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable Long id) {
        perks.delete(authentication.getName(), id);
    }

    @PostMapping("/{id}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(Authentication authentication, @PathVariable Long id,
                        @Valid @RequestBody PublishRequest request) {
        perks.setPublished(authentication.getName(), id, request.published());
    }

    public record PublishRequest(@NotNull Boolean published) {
    }
}
