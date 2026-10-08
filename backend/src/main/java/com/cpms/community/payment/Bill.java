package com.cpms.community.payment;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
@Entity @Table(name="payment_bills")
public class Bill {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false) public String community;
 @Column(nullable=false) public Long residentId;
 @Column(nullable=false) public String residentName;
 public String room;
 @Column(nullable=false) public Long createdBy;
 @Column(nullable=false,length=100) public String title;
 @Column(length=1000) public String description;
 @Column(nullable=false,precision=12,scale=2) public BigDecimal amount;
 public String currency="USD";
 @Column(nullable=false) public LocalDate dueDate;
 public String status="UNPAID";
 public Instant createdAt=Instant.now();
 public Instant paidAt;
 @Column(unique=true) public String receiptNumber;
 @Version public Long version;
}
