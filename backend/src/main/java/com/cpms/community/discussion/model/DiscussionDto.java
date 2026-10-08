
package com.cpms.community.discussion.model;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public class DiscussionDto {
    public enum Category { LIFE, FEEDBACK, TRADING, ACTIVITY }

    public record SaveRequest(
            @NotBlank @Size(max = 25) String title,
            @NotBlank @Size(max = 1000) String content,
            @NotNull Category category) {
    }

    public record Summary(
            Long id,
            String title,
            String category,
            String authorName,
            String authorRole,
            Instant createdAt,
            int likeCount,
            int commentCount,
            boolean pinned,
            boolean liked,
            boolean deleted) {
    }

    public record Detail(
            Long id,
            String community,
            String title,
            String content,
            String category,
            String authorName,
            String authorRole,
            Instant createdAt,
            Instant updatedAt,
            int likeCount,
            int commentCount,
            boolean pinned,
            boolean liked,
            boolean deleted,
            String deleteReason,
            List<CommentDto.View> comments,
            boolean owned,
            /** The author's account when the author is a resident, so others can message them; null for managers and deleted posts. */
            Long authorAccountId) {
    }
}
