
package com.cpms.community.discussion.controller;

import com.cpms.community.discussion.model.DiscussionDto;
import com.cpms.community.discussion.service.DiscussionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/discussions")
public class DiscussionController {
    private final DiscussionService discussions;

    public DiscussionController(DiscussionService discussions) {
        this.discussions = discussions;
    }

    @GetMapping
    public List<DiscussionDto.Summary> list(Authentication authentication,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false, defaultValue = "new") String sort) {
        return discussions.list(authentication.getName(), category, sort);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DiscussionDto.Detail create(Authentication authentication,
                                       @Valid @RequestBody DiscussionDto.SaveRequest request) {
        return discussions.create(authentication.getName(), request);
    }

    @GetMapping("/{id}")
    public DiscussionDto.Detail detail(Authentication authentication, @PathVariable Long id) {
        return discussions.detail(authentication.getName(), id);
    }

    @PutMapping("/{id}")
    public DiscussionDto.Detail update(Authentication authentication, @PathVariable Long id,
                                       @Valid @RequestBody DiscussionDto.SaveRequest request) {
        return discussions.update(authentication.getName(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable Long id) {
        discussions.deleteOwn(authentication.getName(), id);
    }

    @PostMapping("/{id}/like")
    public Map<String, Boolean> toggleLike(Authentication authentication, @PathVariable Long id) {
        return Map.of("liked", discussions.toggleLike(authentication.getName(), id));
    }
}
