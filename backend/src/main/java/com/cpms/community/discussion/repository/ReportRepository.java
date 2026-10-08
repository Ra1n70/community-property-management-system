package com.cpms.community.discussion.repository;

import com.cpms.community.discussion.entity.ReportEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<ReportEntity, Long> {
    java.util.List<ReportEntity> findByCommunityAndStatusOrderByCreatedAtDesc(String community, ReportEntity.Status status);
    java.util.List<ReportEntity> findByCommunityOrderByCreatedAtDesc(String community);
    Optional<ReportEntity> findByIdAndCommunity(Long id, String community);
    boolean existsByTargetTypeAndTargetIdAndReporterId(ReportEntity.TargetType targetType, Long targetId, Long reporterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReportEntity r where r.id = :id and r.community = :community")
    Optional<ReportEntity> lockByIdAndCommunity(@Param("id") Long id, @Param("community") String community);
}
