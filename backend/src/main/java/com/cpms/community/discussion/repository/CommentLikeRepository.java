package com.cpms.community.discussion.repository;

import com.cpms.community.discussion.entity.CommentLikeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CommentLikeRepository extends JpaRepository<CommentLikeEntity, Long> {
    Optional<CommentLikeEntity> findByCommentIdAndAccountId(Long commentId, Long accountId);
    boolean existsByCommentIdAndAccountId(Long commentId, Long accountId);
    long deleteByCommentIdAndAccountId(Long commentId, Long accountId);
}
