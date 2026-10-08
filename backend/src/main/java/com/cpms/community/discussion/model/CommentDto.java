package com.cpms.community.discussion.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public class CommentDto {
    public record SaveRequest(@NotBlank @Size(max = 1000) String content) {
    }

    public record View(
            Long id,
            Long discussionId,
            String content,
            String authorName,
            String authorRole,
            Instant createdAt,
            Instant updatedAt,
            int likeCount,
            boolean liked,
            boolean deleted,
            String deleteReason,
            boolean owned,
            /** The author's account when the author is a resident, so others can message them; null for managers and deleted comments. */
            Long authorAccountId) {
    }
}
