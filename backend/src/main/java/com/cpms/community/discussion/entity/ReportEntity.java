package com.cpms.community.discussion.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reports", uniqueConstraints =
        @UniqueConstraint(name = "uk_report_target_reporter",
                columnNames = {"target_type", "target_id", "reporter_id"}),
        indexes = {
                @Index(name = "idx_reports_community_status", columnList = "community,status"),
                @Index(name = "idx_reports_target", columnList = "target_type,target_id")
        })
public class ReportEntity {
    public enum TargetType { DISCUSSION, COMMENT }
    public enum Reason { AD, ATTACK, FALSE_INFO, OTHER }
    public enum Status { PENDING, PROCESSED }
    public enum Action { DELETE, IGNORE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String community;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    public TargetType targetType;

    @Column(name = "target_id", nullable = false)
    public Long targetId;

    @Column(nullable = false)
    public Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Reason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status;

    public Long handledById;

    public String handledByName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    public Action handledAction;

    public Instant handledAt;

    @Column(nullable = false)
    public Instant createdAt;

    @Version
    public Long version;

    public ReportEntity() {
    }
}
