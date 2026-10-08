package com.cpms.community.discussion.repository;

import com.cpms.community.discussion.entity.DiscussionLikeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DiscussionLikeRepository extends JpaRepository<DiscussionLikeEntity, Long> {
    Optional<DiscussionLikeEntity> findByDiscussionIdAndAccountId(Long discussionId, Long accountId);
    boolean existsByDiscussionIdAndAccountId(Long discussionId, Long accountId);
    long deleteByDiscussionIdAndAccountId(Long discussionId, Long accountId);
}
