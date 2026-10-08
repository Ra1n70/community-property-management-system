package com.cpms.community.lease;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** A lease or related PDF that property management shares with one resident. */
@Entity
@Table(name = "lease_documents", indexes = {
        @Index(name = "idx_lease_documents_resident", columnList = "resident_id, uploaded_at"),
        @Index(name = "idx_lease_documents_community", columnList = "community, uploaded_at")})
public class LeaseDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String community;
    @Column(name = "resident_id", nullable = false) public Long residentId;
    @Column(nullable = false) public String residentName;
    public String room;
    @Column(nullable = false, length = 160) public String title;
    public LocalDate startsOn;
    public LocalDate endsOn;
    @Column(nullable = false, length = 160) public String filename;
    @Column(nullable = false, length = 80) public String storageKey;
    @Column(nullable = false) public long sizeBytes;
    @Column(nullable = false) public Long uploadedBy;
    @Column(nullable = false) public String uploadedByName;
    @Column(name = "uploaded_at", nullable = false) public Instant uploadedAt = Instant.now();
}
