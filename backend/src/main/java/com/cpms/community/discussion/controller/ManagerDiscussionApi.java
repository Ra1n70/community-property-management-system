package com.cpms.community.discussion.controller;

import com.cpms.community.discussion.model.DiscussionDto;
import com.cpms.community.discussion.model.ReportDto;
import com.cpms.community.discussion.service.DiscussionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/manager/discussions")
public class ManagerDiscussionApi {
    private final DiscussionService discussions;

    public ManagerDiscussionApi(DiscussionService discussions) {
        this.discussions = discussions;
    }

    @GetMapping
    public List<DiscussionDto.Summary> list(Authentication authentication) {
        return discussions.managerList(authentication.getName());
    }

    @GetMapping("/{id}")
    public DiscussionDto.Detail detail(Authentication authentication, @PathVariable Long id) {
        return discussions.managerDetail(authentication.getName(), id);
    }

    @PostMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pin(Authentication authentication, @PathVariable Long id,
                    @Valid @RequestBody PinRequest request) {
        discussions.setPinned(authentication.getName(), id, request.pinned());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable Long id,
                       @Valid @RequestBody ReportDto.DeleteReason reason) {
        discussions.managerDelete(authentication.getName(), id, reason.reason());
    }

    public record PinRequest(@NotNull Boolean pinned) {
    }
}
