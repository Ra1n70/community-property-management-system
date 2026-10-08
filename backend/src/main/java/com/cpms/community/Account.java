package com.cpms.community;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class Account {
    public enum Role { RESIDENT, MANAGER, PROVIDER }
    public enum Status { PENDING, APPROVED, REJECTED }
    public enum ProviderType { MAINTENANCE, DELIVERY }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String email;
    @Column(nullable = false) public String passwordHash;
    public String recoveryHash;
    public String sessionVersion;
    @Column(nullable = false) public String name;
    public String room;
    @Column(nullable = false) public String community;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public Role role;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public Status status;
    @Enumerated(EnumType.STRING) public ProviderType providerType;
    public String rejectionReason;
    public Long reviewedBy;
    public Instant reviewedAt;
    public Instant submittedAt = Instant.now();
    @Version public Long version;
    public Account() {}
}
