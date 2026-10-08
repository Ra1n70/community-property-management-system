package com.cpms.community.announcement.controller;

import com.cpms.community.announcement.dto.AnnouncementRequest;
import com.cpms.community.announcement.dto.AnnouncementResponse;
import com.cpms.community.announcement.service.AnnouncementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {
    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public List<AnnouncementResponse> findAll(Authentication auth) {
        return service.findAll(auth.getName());
    }

    @GetMapping("/{id}")
    public AnnouncementResponse findById(Authentication auth, @PathVariable Long id) {
        return service.findById(auth.getName(),id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementResponse create(Authentication auth, @Valid @RequestBody AnnouncementRequest request) {
        return service.create(auth.getName(),request);
    }

    @PutMapping("/{id}")
    public AnnouncementResponse update(Authentication auth, @PathVariable Long id, @Valid @RequestBody AnnouncementRequest request) {
        return service.update(auth.getName(),id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable Long id, @RequestParam Long version) {
        service.delete(auth.getName(),id,version);
    }
}
