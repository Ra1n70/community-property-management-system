package com.cpms.community.maintenance;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="maintenance_events")
public class MaintenanceEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false) public Long ticketId;
    public Long actorId;
    public String actorName;
    public String action;
    @Column(length=2000) public String note;
    public Instant createdAt=Instant.now();
}
