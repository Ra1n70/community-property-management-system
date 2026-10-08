package com.cpms.community.poll;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A community vote created by a manager. Each approved resident can vote once, until closesAt or an early close. */
@Entity
@Table(name = "polls", indexes = @Index(name = "idx_polls_community_created", columnList = "community, created_at"))
public class Poll {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String community;
    @Column(nullable = false, length = 160) public String title;
    @Column(length = 2000) public String description;
    @Column(nullable = false) public Long createdBy;
    @Column(nullable = false) public String createdByName;
    @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
    @Column(nullable = false) public Instant closesAt;
    /** Set when a manager closes the vote before its deadline. */
    public Instant closedAt;
    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    public List<PollOption> options = new ArrayList<>();
    @Version public Long version;

    public boolean open(Instant now) { return closedAt == null && now.isBefore(closesAt); }
}
