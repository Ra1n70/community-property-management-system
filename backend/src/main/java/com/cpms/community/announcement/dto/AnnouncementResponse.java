package com.cpms.community.announcement.dto;

import com.cpms.community.announcement.model.Announcement;

import java.time.Instant;

public record AnnouncementResponse(
        Long id,
        String title,
        String author,
        Instant publishedAt,
        String content,
        Long version
) {
    public static AnnouncementResponse from(Announcement announcement) {
        return new AnnouncementResponse(
                announcement.getId(), announcement.getTitle(), announcement.getAuthor(),
                announcement.getPublishedAt(), announcement.getContent(), announcement.getVersion()
        );
    }
}
