package com.cpms.community;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class RoomChange {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long accountId;
    public Long managerId;
    public String oldRoom;
    public String newRoom;
    @Column(length=1000) public String reason;
    public Instant createdAt=Instant.now();
}
