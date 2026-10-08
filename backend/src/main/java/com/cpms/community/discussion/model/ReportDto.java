package com.cpms.community.discussion.model;

import com.cpms.community.discussion.entity.ReportEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

public class ReportDto {
    public record CreateRequest(
            @NotNull ReportEntity.TargetType targetType,
            @NotNull @Positive Long targetId,
            @NotNull ReportEntity.Reason reason) {
    }

    public record HandleRequest(
            @NotNull ReportEntity.Action action,
            @Valid DeleteReason deleteReason) {
    }

    public record DeleteReason(@NotBlank @Size(max = 500) String reason) {
    }

    public record View(
            Long id,
            String community,
            ReportEntity.TargetType targetType,
            Long targetId,
            Long reporterId,
            ReportEntity.Reason reason,
            ReportEntity.Status status,
            Long handledById,
            String handledByName,
            ReportEntity.Action handledAction,
            Instant handledAt,
            Instant createdAt,
            String targetContent,
            /** Resident who wrote the reported content, so the manager can message them; null for manager authors. */
            Long targetAuthorId,
            String targetAuthorName) {
    }
}
