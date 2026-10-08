package com.cpms.community.announcement.dto;
import jakarta.validation.constraints.*;
import java.time.Instant;
public record AnnouncementRequest(
    @NotBlank @Size(max=160) String title,
    @NotNull Instant publishedAt,
    @NotBlank @Size(max=20000) String content,
    Long version
) {}
