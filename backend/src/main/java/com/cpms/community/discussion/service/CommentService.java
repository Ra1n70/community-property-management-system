package com.cpms.community.discussion.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.discussion.entity.CommentEntity;
import com.cpms.community.discussion.entity.CommentLikeEntity;
import com.cpms.community.discussion.entity.DiscussionEntity;
import com.cpms.community.discussion.model.CommentDto;
import com.cpms.community.discussion.repository.CommentLikeRepository;
import com.cpms.community.discussion.repository.CommentRepository;
import com.cpms.community.discussion.repository.DiscussionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class CommentService {
    private static final String MANAGER_DELETED_MESSAGE = "This content has been deleted.";

    private final CommentRepository comments;
    private final DiscussionRepository discussions;
    private final CommentLikeRepository likes;
    private final DiscussionAccess access;

    public CommentService(CommentRepository comments, DiscussionRepository discussions,
                          CommentLikeRepository likes, DiscussionAccess access) {
        this.comments = comments;
        this.discussions = discussions;
        this.likes = likes;
        this.access = access;
    }

    @Transactional
    public CommentDto.View create(String email, Long discussionId, CommentDto.SaveRequest request) {
        Account account = access.requireParticipant(email);
        DiscussionEntity post = discussions.lockByIdAndCommunity(discussionId, account.community)
                .orElseThrow(this::discussionNotFound);
        if (post.deleted) {
            throw discussionNotFound();
        }
        Instant now = Instant.now();
        CommentEntity comment = new CommentEntity();
        comment.community = account.community;
        comment.discussionId = discussionId;
        comment.authorId = account.id;
        comment.authorName = account.name;
        comment.authorRole = account.role.name();
        comment.content = request.content().strip();
        comment.likeCount = 0;
        comment.deleted = false;
        comment.createdAt = now;
        comment.updatedAt = now;
        comments.saveAndFlush(comment);
        post.commentCount++;
        return toView(comment, account);
    }

    @Transactional(readOnly = true)
    public List<CommentDto.View> viewsFor(Account viewer, Long discussionId) {
        DiscussionEntity post = discussions.findByIdAndCommunity(discussionId, viewer.community)
                .orElseThrow(this::discussionNotFound);
        if (post.deleted && viewer.role == Account.Role.RESIDENT) {
            return List.of();
        }
        return comments.findByDiscussionIdOrderByCreatedAtAsc(discussionId).stream()
                .filter(comment -> viewer.role == Account.Role.MANAGER
                        || !comment.deleted
                        || comment.deleteReason != null && !comment.deleteReason.isBlank())
                .map(comment -> toResidentSafeView(comment, viewer))
                .toList();
    }

    @Transactional
    public CommentDto.View update(String email, Long commentId, CommentDto.SaveRequest request) {
        Account account = access.requireParticipant(email);
        CommentEntity comment = lockInCommunity(account, commentId);
        if (!comment.authorId.equals(account.id)) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "You can edit only your own comment.");
        }
        if (comment.deleted) {
            throw commentNotFound();
        }
        comment.content = request.content().strip();
        comment.updatedAt = Instant.now();
        return toView(comment, account);
    }

    @Transactional
    public void deleteOwn(String email, Long commentId) {
        Account account = access.requireParticipant(email);
        CommentEntity comment = lockInCommunity(account, commentId);
        if (!comment.authorId.equals(account.id)) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "You can delete only your own comment.");
        }
        softDelete(comment, account, null);
    }

    @Transactional
    public void managerDelete(String email, Long commentId, String reason) {
        Account manager = access.requireManager(email);
        CommentEntity comment = lockInCommunity(manager, commentId);
        if (comment.deleted) {
            throw AccountService.fail(HttpStatus.CONFLICT, "This comment is already deleted.");
        }
        softDelete(comment, manager, reason);
    }

    @Transactional
    public boolean toggleLike(String email, Long commentId) {
        Account resident = access.requireApprovedResident(email);
        CommentEntity comment = lockInCommunity(resident, commentId);
        if (comment.deleted) {
            throw commentNotFound();
        }
        return likes.findByCommentIdAndAccountId(commentId, resident.id)
                .map(existing -> {
                    likes.delete(existing);
                    comment.likeCount--;
                    return false;
                })
                .orElseGet(() -> {
                    CommentLikeEntity like = new CommentLikeEntity();
                    like.commentId = commentId;
                    like.accountId = resident.id;
                    like.createdAt = Instant.now();
                    likes.save(like);
                    comment.likeCount++;
                    return true;
                });
    }

    private void softDelete(CommentEntity comment, Account actor, String reason) {
        if (comment.deleted) {
            return;
        }
        comment.deleted = true;
        comment.deleteReason = reason;
        comment.deletedById = actor.id;
        comment.deletedAt = Instant.now();
        DiscussionEntity post = discussions.lockByIdAndCommunity(comment.discussionId, actor.community)
                .orElseThrow(this::discussionNotFound);
        post.commentCount--;
    }

    private CommentEntity lockInCommunity(Account account, Long commentId) {
        CommentEntity comment = comments.lockByIdAndCommunity(commentId, account.community)
                .orElseThrow(this::commentNotFound);
        DiscussionEntity post = discussions.findByIdAndCommunity(comment.discussionId, account.community)
                .orElseThrow(this::discussionNotFound);
        if (account.role == Account.Role.RESIDENT && post.deleted) {
            throw discussionNotFound();
        }
        return comment;
    }

    private CommentDto.View toResidentSafeView(CommentEntity comment, Account viewer) {
        if (viewer.role == Account.Role.MANAGER || !comment.deleted) {
            return toView(comment, viewer);
        }
        return new CommentDto.View(
                comment.id,
                comment.discussionId,
                MANAGER_DELETED_MESSAGE,
                null,
                null,
                comment.createdAt,
                comment.updatedAt,
                comment.likeCount,
                false,
                true,
                null, false, null);
    }

    private CommentDto.View toView(CommentEntity comment, Account viewer) {
        boolean liked = viewer.role == Account.Role.RESIDENT
                && likes.existsByCommentIdAndAccountId(comment.id, viewer.id);
        return new CommentDto.View(
                comment.id,
                comment.discussionId,
                comment.content,
                comment.authorName,
                comment.authorRole,
                comment.createdAt,
                comment.updatedAt,
                comment.likeCount,
                liked,
                comment.deleted,
                comment.deleteReason, !comment.deleted && comment.authorId.equals(viewer.id),
                !comment.deleted && "RESIDENT".equals(comment.authorRole) ? comment.authorId : null);
    }

    private RuntimeException discussionNotFound() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Discussion post not found.");
    }

    private RuntimeException commentNotFound() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Comment not found.");
    }
}
