package com.cpms.community.amenity.repository;

import com.cpms.community.amenity.entity.Amenity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AmenityRepository extends JpaRepository<Amenity, Long> {
    List<Amenity> findByCommunityOrderByNameAsc(String community);
    Optional<Amenity> findByIdAndCommunity(Long id, String community);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Amenity a where a.id=:id and a.community=:community")
    Optional<Amenity> lockByIdAndCommunity(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("community") String community);
}
