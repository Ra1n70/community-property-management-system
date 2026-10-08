package com.cpms.community.discussion.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.discussion.entity.CommentEntity;
import com.cpms.community.discussion.entity.DiscussionEntity;
import com.cpms.community.discussion.entity.ReportEntity;
import com.cpms.community.discussion.model.ReportDto;
import com.cpms.community.discussion.repository.CommentRepository;
import com.cpms.community.discussion.repository.DiscussionRepository;
import com.cpms.community.discussion.repository.ReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class ReportService {
    private final ReportRepository reports;
    private final DiscussionRepository discussions;
    private final CommentRepository comments;
    private final DiscussionAccess access;

    public ReportService(ReportRepository reports, DiscussionRepository discussions,
                         CommentRepository comments, DiscussionAccess access) {
        this.reports = reports;
        this.discussions = discussions;
        this.comments = comments;
        this.access = access;
    }

    @Transactional
    public ReportDto.View submit(String email, ReportDto.CreateRequest request) {
        Account resident = access.requireApprovedResident(email);
        String community = requireVisibleTargetCommunity(resident, request.targetType(), request.targetId());
        if (reports.existsByTargetTypeAndTargetIdAndReporterId(request.targetType(), request.targetId(), resident.id)) {
            throw AccountService.fail(HttpStatus.CONFLICT, "You have already reported this content.");
        }
        ReportEntity report = new ReportEntity();
        report.community = community;
        report.targetType = request.targetType();
        report.targetId = request.targetId();
        report.reporterId = resident.id;
        report.reason = request.reason();
        report.status = ReportEntity.Status.PENDING;
        report.createdAt = Instant.now();
        reports.saveAndFlush(report);
        return toView(report);
    }

    @Transactional(readOnly = true)
    public List<ReportDto.View> list(String email, String status) {
        Account manager = access.requireManager(email);
        List<ReportEntity> result = parseStatus(status) == null
                ? reports.findByCommunityOrderByCreatedAtDesc(manager.community)
                : reports.findByCommunityAndStatusOrderByCreatedAtDesc(manager.community, parseStatus(status));
        return result.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ReportDto.View detail(String email, Long id) {
        Account manager = access.requireManager(email);
        ReportEntity report = reports.findByIdAndCommunity(id, manager.community)
                .orElseThrow(this::notFound);
        return toView(report);
    }

    @Transactional
    public ReportDto.View handle(String email, Long id, ReportDto.HandleRequest request) {
        Account manager = access.requireManager(email);
        ReportEntity report = reports.lockByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        if (report.status != ReportEntity.Status.PENDING) {
            throw AccountService.fail(HttpStatus.CONFLICT, "This report has already been handled.");
        }

        if (request.action() == ReportEntity.Action.DELETE) {
            String reason = request.deleteReason() == null ? null : request.deleteReason().reason();
            if (reason == null || reason.isBlank()) {
                throw AccountService.fail(HttpStatus.BAD_REQUEST, "A deletion reason is required.");
            }
            deleteTarget(manager, report.targetType, report.targetId, reason.strip());
        }

        report.status = ReportEntity.Status.PROCESSED;
        report.handledById = manager.id;
        report.handledByName = manager.name;
        report.handledAction = request.action();
        report.handledAt = Instant.now();
        return toView(report);
    }

    private String requireVisibleTargetCommunity(Account resident, ReportEntity.TargetType targetType, Long targetId) {
        if (targetType == ReportEntity.TargetType.DISCUSSION) {
            DiscussionEntity post = discussions.lockByIdAndCommunity(targetId, resident.community)
                    .orElseThrow(this::targetNotFound);
            if (post.deleted) {
                throw targetNotFound();
            }
            return post.community;
        }
        CommentEntity comment = comments.lockByIdAndCommunity(targetId, resident.community)
                .orElseThrow(this::targetNotFound);
        DiscussionEntity post = discussions.findByIdAndCommunity(comment.discussionId, resident.community)
                .orElseThrow(this::targetNotFound);
        if (comment.deleted || post.deleted) {
            throw targetNotFound();
        }
        return comment.community;
    }

    private void deleteTarget(Account manager, ReportEntity.TargetType targetType, Long targetId, String reason) {
        if (targetType == ReportEntity.TargetType.DISCUSSION) {
            DiscussionEntity post = discussions.lockByIdAndCommunity(targetId, manager.community)
                    .orElseThrow(this::targetNotFound);
            if (post.deleted) {
                throw AccountService.fail(HttpStatus.CONFLICT, "Target is already deleted; ignore this report instead.");
            }
            post.deleted = true;
            post.deleteReason = reason;
            post.deletedById = manager.id;
            post.deletedAt = Instant.now();
            return;
        }

        CommentEntity comment = comments.lockByIdAndCommunity(targetId, manager.community)
                .orElseThrow(this::targetNotFound);
        if (comment.deleted) {
            throw AccountService.fail(HttpStatus.CONFLICT, "Target is already deleted; ignore this report instead.");
        }
        DiscussionEntity post = discussions.lockByIdAndCommunity(comment.discussionId, manager.community)
                .orElseThrow(this::targetNotFound);
        comment.deleted = true;
        comment.deleteReason = reason;
        comment.deletedById = manager.id;
        comment.deletedAt = Instant.now();
        post.commentCount--;
    }

    private ReportEntity.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReportEntity.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid report status.");
        }
    }

    private ReportDto.View toView(ReportEntity report) {
        Author author = targetAuthor(report);
        return new ReportDto.View(
                report.id,
                report.community,
                report.targetType,
                report.targetId,
                report.reporterId,
                report.reason,
                report.status,
                report.handledById,
                report.handledByName,
                report.handledAction,
                report.handledAt,
                report.createdAt,
                buildTargetContent(report),
                author == null ? null : author.id(),
                author == null ? null : author.name());
    }

    private record Author(Long id, String name) {
    }

    private Author targetAuthor(ReportEntity report) {
        if (report.targetType == ReportEntity.TargetType.DISCUSSION) {
            return discussions.findByIdAndCommunity(report.targetId, report.community)
                    .filter(post -> "RESIDENT".equals(post.authorRole))
                    .map(post -> new Author(post.authorId, post.authorName)).orElse(null);
        }
        return comments.findByIdAndCommunity(report.targetId, report.community)
                .filter(comment -> "RESIDENT".equals(comment.authorRole))
                .map(comment -> new Author(comment.authorId, comment.authorName)).orElse(null);
    }

    private String buildTargetContent(ReportEntity report) {
        if (report.targetType == ReportEntity.TargetType.DISCUSSION) {
            return discussions.findByIdAndCommunity(report.targetId, report.community)
                    .map(post -> post.title + ": " + post.content)
                    .orElse("This content is no longer available.");
        }
        return comments.findByIdAndCommunity(report.targetId, report.community)
                .map(comment -> comment.content)
                .orElse("This content is no longer available.");
    }

    private RuntimeException notFound() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Report not found.");
    }

    private RuntimeException targetNotFound() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Reported content not found.");
    }
}
