package com.cpms.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class Api {
    public record Register(@NotBlank @Size(max=100) String name, @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=10,max=64) String password, @NotBlank @Size(max=30) String room, @NotBlank String inviteCode) {}
    public record Resubmit(@NotBlank @Size(max=100) String name, @NotBlank @Size(max=30) String room) {}
    public record Decision(@NotNull Account.Status status, @Size(max=1000) String reason) {}
    public record AccountView(Long id, String name, String email, String room, String community, Account.Role role,
                              Account.Status status, String rejectionReason, Instant submittedAt, Instant reviewedAt, Long reviewedBy, boolean hasRecoveryCode, String recoveryCode, Account.ProviderType providerType) {
        static AccountView of(Account a) { return of(a,null); }
        static AccountView of(Account a,String code) { return new AccountView(a.id,a.name,a.email,a.room,a.community,a.role,a.status,a.rejectionReason,a.submittedAt,a.reviewedAt,a.reviewedBy,a.recoveryHash!=null,code,a.providerType); }
    }
    private final AccountService service;
    private final AccountRepository accounts;
    private final ReviewRepository reviews;
    private final RecoveryService recovery;
    public Api(AccountService service, AccountRepository accounts, ReviewRepository reviews, RecoveryService recovery) {this.service=service;this.accounts=accounts;this.reviews=reviews;this.recovery=recovery;}
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) {return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public AccountView register(@Valid @RequestBody Register input) {
        Account a=service.register(input);String code=recovery.issue(a.email,null);
        return AccountView.of(service.current(a.email),code);
    }
    @GetMapping("/auth/me") public AccountView me(Authentication auth) {return AccountView.of(service.current(auth.getName()));}
    @PostMapping("/auth/resubmit") public AccountView resubmit(Authentication auth,@Valid @RequestBody Resubmit input) {return AccountView.of(service.resubmit(auth.getName(),input));}
    @GetMapping("/manager/applications") public List<AccountView> applications(Authentication auth,@RequestParam(required=false) Account.Status status) {
        return accounts.findByCommunityAndRoleOrderBySubmittedAtDesc(service.current(auth.getName()).community,Account.Role.RESIDENT)
            .stream().filter(a -> status==null || a.status==status).map(AccountView::of).toList();
    }
    @GetMapping("/manager/applications/{id}/history") public List<ReviewEvent> history(Authentication auth,@PathVariable Long id) {
        Account a=accounts.findById(id).orElseThrow(()->AccountService.fail(HttpStatus.NOT_FOUND,"Application not found."));
        if(a.role!=Account.Role.RESIDENT || !a.community.equals(service.current(auth.getName()).community))throw AccountService.fail(HttpStatus.NOT_FOUND,"Application not found.");
        return reviews.findByAccountIdOrderByCreatedAtDesc(id);
    }
    @PostMapping("/manager/applications/{id}/review") public AccountView review(Authentication auth,@PathVariable Long id,@Valid @RequestBody Decision input) {return AccountView.of(service.review(auth.getName(),id,input));}
    // Other resident modules must reuse this live database authorization check.
    @GetMapping("/resident/welcome") public Map<String,String> welcome(Authentication auth) {
        Account a=service.current(auth.getName());
        if(a.role!=Account.Role.RESIDENT || a.status!=Account.Status.APPROVED)throw AccountService.fail(HttpStatus.FORBIDDEN,"Resident approval is required.");
        return Map.of("message","Your resident access is active.","community",a.community);
    }
}
