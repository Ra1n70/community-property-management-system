package com.cpms.community.discussion.controller;

import com.cpms.community.discussion.model.ReportDto;
import com.cpms.community.discussion.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportApi {
    private final ReportService reports;

    public ReportApi(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDto.View submit(Authentication authentication,
                                 @Valid @RequestBody ReportDto.CreateRequest request) {
        return reports.submit(authentication.getName(), request);
    }
}
