package com.cpms.community.support;

import jakarta.persistence.*;
import java.time.Instant;

/** A frequently asked question with its answer, shown beside the Messages conversation. */
@Entity
@Table(name = "support_faqs", indexes = @Index(name = "idx_support_faqs_community", columnList = "community, position"))
public class SupportFaq {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String community;
    @Column(nullable = false, length = 200) public String question;
    @Column(nullable = false, length = 2000) public String answer;
    @Column(nullable = false) public int position;
    @Column(nullable = false) public Instant updatedAt = Instant.now();
    @Version public Long version;
}
