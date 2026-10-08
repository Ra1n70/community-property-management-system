package com.cpms.community.discussion.repository;

import com.cpms.community.discussion.entity.CommentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CommentRepository extends JpaRepository<CommentEntity, Long> {
    java.util.List<CommentEntity> findByDiscussionIdOrderByCreatedAtAsc(Long discussionId);
    Optional<CommentEntity> findByIdAndCommunity(Long id, String community);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommentEntity c where c.id = :id and c.community = :community")
    Optional<CommentEntity> lockByIdAndCommunity(@Param("id") Long id, @Param("community") String community);
}
