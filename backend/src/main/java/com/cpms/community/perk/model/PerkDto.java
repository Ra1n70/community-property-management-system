package com.cpms.community.perk.model;

import jakarta.validation.constraints.*;
import java.time.Instant;

public class PerkDto {
    public enum Category { DINING, SHOPPING, SERVICES, ENTERTAINMENT, HEALTH, OTHER }

    public record SaveRequest(
            @NotBlank @Size(max = 60) String businessName,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 1000) String description,
            @NotNull Category category,
            @Size(max = 40) String contact,
            @Size(max = 160) String address,
            @Size(max = 200) String website,
            Instant startAt,
            @NotNull Instant endAt) {
    }

    public record Summary(
            Long id,
            String businessName,
            String title,
            String category,
            Instant startAt,
            Instant endAt,
            boolean active,
            boolean published) {
    }

    public record Detail(
            Long id,
            String community,
            String businessName,
            String title,
            String description,
            String category,
            String contact,
            String address,
            String website,
            Instant startAt,
            Instant endAt,
            boolean active,
            boolean published,
            Long createdById,
            Instant createdAt,
            Instant updatedAt) {
    }
}
