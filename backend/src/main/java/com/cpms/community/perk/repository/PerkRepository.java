package com.cpms.community.perk.repository;

import com.cpms.community.perk.entity.PerkEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PerkRepository extends JpaRepository<PerkEntity, Long> {
    List<PerkEntity> findByCommunityAndPublishedTrueOrderByCreatedAtDesc(String community);
    List<PerkEntity> findByCommunityOrderByCreatedAtDesc(String community);
    Optional<PerkEntity> findByIdAndCommunity(Long id, String community);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PerkEntity p where p.id = :id and p.community = :community")
    Optional<PerkEntity> lockByIdAndCommunity(@Param("id") Long id, @Param("community") String community);
}
