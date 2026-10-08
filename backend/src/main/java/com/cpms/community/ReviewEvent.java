package com.cpms.community;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class ReviewEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long accountId;
    public Long reviewerId;
    public String decision;
    public String reason;
    public Instant createdAt = Instant.now();
    public ReviewEvent() {}
}
