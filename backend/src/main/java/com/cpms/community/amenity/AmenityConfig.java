package com.cpms.community.amenity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties(AmenityProperties.class)
public class AmenityConfig {
    public static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

    @Bean
    Clock clock() {
        return Clock.system(ZONE);
    }
}
