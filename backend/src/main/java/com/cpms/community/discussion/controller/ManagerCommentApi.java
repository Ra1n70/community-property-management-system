package com.cpms.community.discussion.controller;

import com.cpms.community.discussion.model.ReportDto;
import com.cpms.community.discussion.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manager/comments")
public class ManagerCommentApi {
    private final CommentService comments;

    public ManagerCommentApi(CommentService comments) {
        this.comments = comments;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable Long id,
                       @Valid @RequestBody ReportDto.DeleteReason reason) {
        comments.managerDelete(authentication.getName(), id, reason.reason());
    }
}
