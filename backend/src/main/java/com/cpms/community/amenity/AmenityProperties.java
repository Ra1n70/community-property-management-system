package com.cpms.community.amenity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "amenity")
public record AmenityProperties(String uploadDir, boolean seed) {}
