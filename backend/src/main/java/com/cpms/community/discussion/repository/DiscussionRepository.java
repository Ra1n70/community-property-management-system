
package com.cpms.community.discussion.repository;

import com.cpms.community.discussion.entity.DiscussionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DiscussionRepository extends JpaRepository<DiscussionEntity, Long> {
    List<DiscussionEntity> findByCommunityAndDeletedFalseOrderByCreatedAtDesc(String community);
    List<DiscussionEntity> findByCommunityOrderByCreatedAtDesc(String community);
    Optional<DiscussionEntity> findByIdAndCommunity(Long id, String community);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DiscussionEntity d where d.id = :id and d.community = :community")
    Optional<DiscussionEntity> lockByIdAndCommunity(@Param("id") Long id, @Param("community") String community);
}
