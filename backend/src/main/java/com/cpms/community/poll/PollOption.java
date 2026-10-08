package com.cpms.community.poll;

import jakarta.persistence.*;

@Entity
@Table(name = "poll_options")
public class PollOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "poll_id", nullable = false) public Poll poll;
    @Column(nullable = false, length = 120) public String label;
    @Column(nullable = false) public int position;
}
