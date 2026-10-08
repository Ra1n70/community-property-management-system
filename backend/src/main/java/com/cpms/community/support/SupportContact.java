package com.cpms.community.support;

import jakarta.persistence.*;
import java.time.Instant;

/** How residents reach the property office, one record per community. */
@Entity
@Table(name = "support_contacts")
public class SupportContact {
    @Id public String community;
    @Column(length = 40) public String officePhone;
    @Column(length = 254) public String email;
    @Column(length = 200) public String officeHours;
    @Column(length = 200) public String address;
    @Column(length = 40) public String emergencyPhone;
    public Instant updatedAt;
    @Version public Long version;
}
