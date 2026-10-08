package com.cpms.community.amenity.repository;

import com.cpms.community.amenity.entity.AmenityWeeklyHour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface AmenityWeeklyHourRepository extends JpaRepository<AmenityWeeklyHour, Long> {
    List<AmenityWeeklyHour> findByAmenityIdOrderByOpenTimeAsc(Long amenityId);
    List<AmenityWeeklyHour> findByAmenityIdAndDayOfWeekOrderByOpenTimeAsc(Long amenityId, DayOfWeek dayOfWeek);
    void deleteByAmenityId(Long amenityId);
}
