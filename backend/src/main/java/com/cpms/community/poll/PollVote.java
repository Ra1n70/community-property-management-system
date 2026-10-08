package com.cpms.community.poll;

import jakarta.persistence.*;
import java.time.Instant;

/** One resident's vote; the unique constraint stops a second vote even when two requests race. */
@Entity
@Table(name = "poll_votes", uniqueConstraints = @UniqueConstraint(name = "uk_poll_votes_poll_resident", columnNames = {"poll_id", "resident_id"}))
public class PollVote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "poll_id", nullable = false) public Long pollId;
    @Column(name = "option_id", nullable = false) public Long optionId;
    @Column(name = "resident_id", nullable = false) public Long residentId;
    @Column(nullable = false) public Instant createdAt = Instant.now();
}
