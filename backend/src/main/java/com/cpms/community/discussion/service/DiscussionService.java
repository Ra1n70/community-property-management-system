
package com.cpms.community.discussion.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.discussion.entity.DiscussionEntity;
import com.cpms.community.discussion.entity.DiscussionLikeEntity;
import com.cpms.community.discussion.model.CommentDto;
import com.cpms.community.discussion.model.DiscussionDto;
import com.cpms.community.discussion.repository.DiscussionLikeRepository;
import com.cpms.community.discussion.repository.DiscussionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class DiscussionService {
    private final DiscussionRepository discussions;
    private final DiscussionLikeRepository likes;
    private final CommentService comments;
    private final DiscussionAccess access;

    public DiscussionService(DiscussionRepository discussions, DiscussionLikeRepository likes,
                             CommentService comments, DiscussionAccess access) {
        this.discussions = discussions;
        this.likes = likes;
        this.comments = comments;
        this.access = access;
    }

    @Transactional
    public DiscussionDto.Detail create(String email, DiscussionDto.SaveRequest request) {
        Account account = access.requireParticipant(email);
        Instant now = Instant.now();
        DiscussionEntity post = new DiscussionEntity();
        post.community = account.community;
        post.authorId = account.id;
        post.authorName = account.name;
        post.authorRole = account.role.name();
        post.title = request.title().strip();
        post.content = request.content().strip();
        post.category = request.category().name();
        post.likeCount = 0;
        post.commentCount = 0;
        post.pinned = false;
        post.deleted = false;
        post.createdAt = now;
        post.updatedAt = now;
        discussions.saveAndFlush(post);
        return toDetail(post, account, List.of());
    }

    @Transactional(readOnly = true)
    public List<DiscussionDto.Summary> list(String email, String category, String sort) {
        Account account = access.requireParticipant(email);
        DiscussionDto.Category categoryFilter = parseCategory(category);
        List<DiscussionEntity> posts = discussions.findByCommunityAndDeletedFalseOrderByCreatedAtDesc(account.community)
                .stream()
                .filter(post -> categoryFilter == null || post.category.equals(categoryFilter.name()))
                .sorted(listComparator(sort))
                .toList();
        return posts.stream().map(post -> toSummary(post, account)).toList();
    }

    @Transactional(readOnly = true)
    public DiscussionDto.Detail detail(String email, Long id) {
        Account account = access.requireParticipant(email);
        DiscussionEntity post = discussions.findByIdAndCommunity(id, account.community)
                .orElseThrow(this::notFound);
        if (post.deleted && account.role == Account.Role.RESIDENT) {
            return deletedPlaceholder(post);
        }
        return toDetail(post, account, comments.viewsFor(account, id));
    }

    @Transactional(readOnly = true)
    public List<DiscussionDto.Summary> managerList(String email) {
        Account manager = access.requireManager(email);
        return discussions.findByCommunityOrderByCreatedAtDesc(manager.community).stream()
                .sorted(Comparator.comparing((DiscussionEntity post) -> !post.pinned)
                        .thenComparing(post -> post.createdAt, Comparator.reverseOrder()))
                .map(post -> toSummary(post, manager))
                .toList();
    }

    @Transactional(readOnly = true)
    public DiscussionDto.Detail managerDetail(String email, Long id) {
        Account manager = access.requireManager(email);
        DiscussionEntity post = discussions.findByIdAndCommunity(id, manager.community)
                .orElseThrow(this::notFound);
        return toDetail(post, manager, comments.viewsFor(manager, id));
    }

    @Transactional
    public DiscussionDto.Detail update(String email, Long id, DiscussionDto.SaveRequest request) {
        Account account = access.requireParticipant(email);
        DiscussionEntity post = discussions.lockByIdAndCommunity(id, account.community).orElseThrow(this::notFound);
        if (!post.authorId.equals(account.id)) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "You can edit only your own post.");
        }
        if (post.deleted) {
            throw notFound();
        }
        post.title = request.title().strip();
        post.content = request.content().strip();
        post.category = request.category().name();
        post.updatedAt = Instant.now();
        return toDetail(post, account, comments.viewsFor(account, id));
    }

    @Transactional
    public void deleteOwn(String email, Long id) {
        Account account = access.requireParticipant(email);
        DiscussionEntity post = discussions.lockByIdAndCommunity(id, account.community).orElseThrow(this::notFound);
        if (!post.authorId.equals(account.id)) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "You can delete only your own post.");
        }
        softDelete(post, account, null);
    }

    @Transactional
    public void managerDelete(String email, Long id, String reason) {
        Account manager = access.requireManager(email);
        DiscussionEntity post = discussions.lockByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        if (post.deleted) {
            throw AccountService.fail(HttpStatus.CONFLICT, "This post is already deleted.");
        }
        softDelete(post, manager, reason);
    }

    @Transactional
    public void setPinned(String email, Long id, boolean pinned) {
        Account manager = access.requireManager(email);
        DiscussionEntity post = discussions.lockByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        if (post.deleted) {
            throw AccountService.fail(HttpStatus.CONFLICT, "Deleted posts cannot be pinned.");
        }
        post.pinned = pinned;
    }

    @Transactional
    public boolean toggleLike(String email, Long id) {
        Account resident = access.requireApprovedResident(email);
        DiscussionEntity post = discussions.lockByIdAndCommunity(id, resident.community).orElseThrow(this::notFound);
        if (post.deleted) {
            throw notFound();
        }
        return likes.findByDiscussionIdAndAccountId(id, resident.id)
                .map(existing -> {
                    likes.delete(existing);
                    post.likeCount--;
                    return false;
                })
                .orElseGet(() -> {
                    DiscussionLikeEntity like = new DiscussionLikeEntity();
                    like.discussionId = id;
                    like.accountId = resident.id;
                    like.createdAt = Instant.now();
                    likes.save(like);
                    post.likeCount++;
                    return true;
                });
    }

    private void softDelete(DiscussionEntity post, Account actor, String reason) {
        if (post.deleted) {
            return;
        }
        post.deleted = true;
        post.deleteReason = reason;
        post.deletedById = actor.id;
        post.deletedAt = Instant.now();
    }

    private DiscussionDto.Category parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return DiscussionDto.Category.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid category.");
        }
    }

    private Comparator<DiscussionEntity> listComparator(String sort) {
        Comparator<DiscussionEntity> base = "likes".equalsIgnoreCase(sort)
                ? Comparator.comparingInt((DiscussionEntity post) -> post.likeCount).reversed()
                : Comparator.comparing((DiscussionEntity post) -> post.createdAt).reversed();
        return Comparator.comparing((DiscussionEntity post) -> !post.pinned)
                .thenComparing(base)
                .thenComparing(post -> post.id, Comparator.reverseOrder());
    }

    private DiscussionDto.Summary toSummary(DiscussionEntity post, Account viewer) {
        return new DiscussionDto.Summary(
                post.id,
                post.title,
                post.category,
                post.authorName,
                post.authorRole,
                post.createdAt,
                post.likeCount,
                post.commentCount,
                post.pinned,
                viewer.role == Account.Role.RESIDENT && likes.existsByDiscussionIdAndAccountId(post.id, viewer.id), post.deleted);
    }

    private DiscussionDto.Detail toDetail(DiscussionEntity post, Account viewer, List<CommentDto.View> commentViews) {
        boolean liked = viewer.role == Account.Role.RESIDENT
                && likes.existsByDiscussionIdAndAccountId(post.id, viewer.id);
        return new DiscussionDto.Detail(
                post.id,
                post.community,
                post.title,
                post.content,
                post.category,
                post.authorName,
                post.authorRole,
                post.createdAt,
                post.updatedAt,
                post.likeCount,
                post.commentCount,
                post.pinned,
                liked,
                post.deleted,
                post.deleteReason,
                commentViews, !post.deleted && post.authorId.equals(viewer.id),
                !post.deleted && "RESIDENT".equals(post.authorRole) ? post.authorId : null);
    }

    private DiscussionDto.Detail deletedPlaceholder(DiscussionEntity post) {
        String message = "This content has been deleted.";
        return new DiscussionDto.Detail(
                post.id,
                post.community,
                message,
                message,
                post.category,
                null,
                null,
                post.createdAt,
                post.updatedAt,
                post.likeCount,
                post.commentCount,
                post.pinned,
                false,
                true,
                null,
                List.of(), false, null);
    }

    private RuntimeException notFound() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Discussion post not found.");
    }
}
