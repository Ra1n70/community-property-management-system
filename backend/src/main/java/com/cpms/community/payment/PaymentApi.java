package com.cpms.community.payment;
import com.cpms.community.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
@RestController @RequestMapping("/api/payments") @Transactional
public class PaymentApi {
 public record Create(@NotNull Long residentId,@NotBlank @Size(max=100) String title,@Size(max=1000) String description,
   @NotNull @DecimalMin("0.01") @DecimalMax("9999999.99") @Digits(integer=7,fraction=2) BigDecimal amount,@NotNull LocalDate dueDate){}
 public record Resident(Long id,String name,String room){}
 public record Pay(@AssertTrue boolean confirmed){}
 private final AccountService auth;private final AccountRepository accounts;private final BillRepository bills;
 public PaymentApi(AccountService auth,AccountRepository accounts,BillRepository bills){this.auth=auth;this.accounts=accounts;this.bills=bills;}
 private Account actor(Authentication authentication){Account a=auth.current(authentication.getName());if(a.status!=Account.Status.APPROVED||a.role==Account.Role.PROVIDER)throw AccountService.fail(HttpStatus.FORBIDDEN,"Payments are available to approved residents and managers.");return a;}
 private void manager(Account a){if(a.role!=Account.Role.MANAGER)throw AccountService.fail(HttpStatus.FORBIDDEN,"Manager access required.");}
 @GetMapping @Transactional(readOnly=true)
 public List<Bill> list(Authentication authentication){Account a=actor(authentication);return a.role==Account.Role.MANAGER?bills.findByCommunityOrderByCreatedAtDesc(a.community):bills.findByCommunityAndResidentIdOrderByCreatedAtDesc(a.community,a.id);}
 @GetMapping("/residents") @Transactional(readOnly=true)
 public List<Resident> residents(Authentication authentication){Account a=actor(authentication);manager(a);return accounts.findByCommunityAndRoleOrderBySubmittedAtDesc(a.community,Account.Role.RESIDENT).stream().filter(r->r.status==Account.Status.APPROVED).map(r->new Resident(r.id,r.name,r.room)).toList();}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public Bill create(Authentication authentication,@Valid @RequestBody Create input){Account a=actor(authentication);manager(a);Account resident=accounts.lockById(input.residentId()).orElseThrow(()->AccountService.fail(HttpStatus.BAD_REQUEST,"Choose an approved resident."));
 if(!resident.community.equals(a.community)||resident.role!=Account.Role.RESIDENT||resident.status!=Account.Status.APPROVED)throw AccountService.fail(HttpStatus.BAD_REQUEST,"Choose an approved resident in your community.");
 if(input.dueDate().isBefore(LocalDate.now(ZoneId.of("America/Los_Angeles"))))throw AccountService.fail(HttpStatus.BAD_REQUEST,"Due date cannot be in the past.");
 Bill b=new Bill();b.community=a.community;b.residentId=resident.id;b.residentName=resident.name;b.room=resident.room;b.createdBy=a.id;b.title=input.title().strip();b.description=input.description();b.amount=input.amount().setScale(2);b.dueDate=input.dueDate();return bills.saveAndFlush(b);}
 @PostMapping("/{id}/pay")
 public Bill pay(Authentication authentication,@PathVariable Long id,@Valid @RequestBody Pay input){Account a=actor(authentication);if(a.role!=Account.Role.RESIDENT)throw AccountService.fail(HttpStatus.FORBIDDEN,"Only the billed resident can simulate payment.");
 Bill b=bills.lock(id,a.community).orElseThrow(()->AccountService.fail(HttpStatus.NOT_FOUND,"Bill not found."));if(!b.residentId.equals(a.id))throw AccountService.fail(HttpStatus.NOT_FOUND,"Bill not found.");
 if(b.status.equals("PAID"))return b;
 b.status="PAID";b.paidAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);b.receiptNumber="DEMO-"+UUID.randomUUID();return bills.saveAndFlush(b);}
}
