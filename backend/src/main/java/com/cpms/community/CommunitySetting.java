package com.cpms.community;
import jakarta.persistence.*;
@Entity
public class CommunitySetting {
    @Id public String community;
    @Column(nullable=false, length=100) public String inviteCode;
    public CommunitySetting() {}
}
