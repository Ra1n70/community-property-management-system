package com.cpms.community.discussion.controller;

import com.cpms.community.discussion.model.CommentDto;
import com.cpms.community.discussion.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CommentApi {
    private final CommentService comments;

    public CommentApi(CommentService comments) {
        this.comments = comments;
    }

    @PostMapping("/api/discussions/{discussionId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDto.View create(Authentication authentication, @PathVariable Long discussionId,
                                  @Valid @RequestBody CommentDto.SaveRequest request) {
        return comments.create(authentication.getName(), discussionId, request);
    }

    @PutMapping("/api/comments/{id}")
    public CommentDto.View update(Authentication authentication, @PathVariable Long id,
                                  @Valid @RequestBody CommentDto.SaveRequest request) {
        return comments.update(authentication.getName(), id, request);
    }

    @DeleteMapping("/api/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable Long id) {
        comments.deleteOwn(authentication.getName(), id);
    }

    @PostMapping("/api/comments/{id}/like")
    public Map<String, Boolean> toggleLike(Authentication authentication, @PathVariable Long id) {
        return Map.of("liked", comments.toggleLike(authentication.getName(), id));
    }
}
