package com.cpms.community.discussion.controller;

import com.cpms.community.discussion.model.ReportDto;
import com.cpms.community.discussion.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/manager/reports")
public class ManagerReportApi {
    private final ReportService reports;

    public ManagerReportApi(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<ReportDto.View> list(Authentication authentication,
                                     @RequestParam(required = false) String status) {
        return reports.list(authentication.getName(), status);
    }

    @GetMapping("/{id}")
    public ReportDto.View detail(Authentication authentication, @PathVariable Long id) {
        return reports.detail(authentication.getName(), id);
    }

    @PostMapping("/{id}/handle")
    public ReportDto.View handle(Authentication authentication, @PathVariable Long id,
                                 @Valid @RequestBody ReportDto.HandleRequest request) {
        return reports.handle(authentication.getName(), id, request);
    }
}
