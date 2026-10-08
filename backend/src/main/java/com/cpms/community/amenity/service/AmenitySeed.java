package com.cpms.community.amenity.service;

import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.amenity.entity.AmenityWeeklyHour;
import com.cpms.community.amenity.repository.AmenityRepository;
import com.cpms.community.amenity.repository.AmenityWeeklyHourRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "amenity.seed", havingValue = "true")
public class AmenitySeed implements ApplicationRunner {
    static final String COMMUNITY = "Demo Community";

    private final AmenityRepository amenities;
    private final AmenityWeeklyHourRepository hours;

    public AmenitySeed(AmenityRepository amenities, AmenityWeeklyHourRepository hours) {
        this.amenities = amenities;
        this.hours = hours;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!amenities.findByCommunityOrderByNameAsc(COMMUNITY).isEmpty()) {
            return;
        }
        Amenity partyRoom = save("Party Room", "Common room", "Clubhouse", 20, true, 50, "Private indoor gathering space.");
        Amenity gym = save("Gym", "Fitness", "Building A, 1F", 10, false, 0, "Shared fitness equipment. Wipe down after use.");
        seedHours(partyRoom.id);
        seedHours(gym.id);
    }

    private Amenity save(
            String name,
            String type,
            String location,
            int capacity,
            boolean chargeable,
            int fee,
            String description
    ) {
        Amenity amenity = new Amenity();
        amenity.community = COMMUNITY;
        amenity.name = name;
        amenity.type = type;
        amenity.location = location;
        amenity.capacity = capacity;
        amenity.slotDurationMinutes = 60;
        amenity.chargeable = chargeable;
        amenity.fee = java.math.BigDecimal.valueOf(fee).setScale(2);
        amenity.description = description;
        amenity.maxSlotsPerDay = 2;
        amenity.maxAdvanceDays = 7;
        Instant now = Instant.now();
        amenity.createdAt = now;
        amenity.updatedAt = now;
        return amenities.save(amenity);
    }

    private void seedHours(Long amenityId) {
        List<AmenityWeeklyHour> rows = new ArrayList<>();
        LocalTime open = LocalTime.of(9, 0);
        LocalTime close = LocalTime.of(21, 0);
        for (DayOfWeek day : DayOfWeek.values()) {
            rows.add(new AmenityWeeklyHour(amenityId, day, open, close));
        }
        hours.saveAll(rows);
    }
}
