package com.cpms.community.maintenance;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name="maintenance_tickets", indexes={@Index(name="idx_maintenance_community",columnList="community"),
        @Index(name="idx_maintenance_community_updated",columnList="community, updated_at"),
        @Index(name="idx_maintenance_resident_updated",columnList="resident_id, updated_at"),
        @Index(name="idx_maintenance_assignee_updated",columnList="assignee_id, updated_at")})
public class MaintenanceTicket {
    /** UNABLE_TO_FIX waits for the manager to reassign or close; UNRESOLVED is the final "repair failed" outcome. */
    public enum Status { PENDING, ACCEPTED, IN_PROGRESS, PENDING_CONFIRMATION, COMPLETED, REJECTED, UNABLE_TO_FIX, UNRESOLVED }
    public enum Outcome { FIXED, UNABLE_TO_FIX }
    public enum Priority { NORMAL, URGENT }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,unique=true) public String ticketNo;
    @Column(nullable=false) public String community;
    @Column(nullable=false) public Long residentId;
    @Column(nullable=false) public String residentName;
    public String room;
    @Column(nullable=false,length=80) public String category;
    @Column(nullable=false,length=200) public String location;
    @Column(nullable=false,length=3000) public String description;
    @ElementCollection(fetch=FetchType.EAGER)
    @CollectionTable(name="maintenance_images",joinColumns=@JoinColumn(name="ticket_id"))
    @OrderColumn(name="image_index") @Column(length=2000) public List<String> imageUrls=new ArrayList<>();
    public Instant preferredTime;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public Status status=Status.PENDING;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public Priority priority=Priority.NORMAL;
    public Long assigneeId;
    public String assigneeName;
    public String assigneeType;
    public Instant targetCompletionTime;
    @Column(length=2000) public String result;
    @Column(length=1000) public String rejectionReason;
    public Instant createdAt=Instant.now();
    public Instant updatedAt=Instant.now();
    public Instant completedAt;
    @Version public Long version;
}
