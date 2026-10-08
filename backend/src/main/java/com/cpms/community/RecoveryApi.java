package com.cpms.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.*;

@RestController @RequestMapping("/api")
public class RecoveryApi {
    private final RecoveryService service;
    private final AccountRepository accounts;
    public RecoveryApi(RecoveryService service,AccountRepository accounts){this.service=service;this.accounts=accounts;}
    public record Passwords(@NotBlank @Size(max=64) String newPassword,@NotBlank @Size(max=64) String confirmation,String currentPassword) {}
    public record Recover(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=100) String code,@NotBlank @Size(max=64) String newPassword,@NotBlank @Size(max=64) String confirmation) {}
    public record Redeem(@NotBlank @Size(max=100) String token,@NotBlank @Size(max=64) String newPassword,@NotBlank @Size(max=64) String confirmation) {}
    public record Issue(@Size(max=64) String currentPassword) {}
    public record Assistance(@NotBlank @Size(max=1000) String note, boolean verified) {}
    @PostMapping("/auth/password") public void change(Authentication a,@Valid @RequestBody Passwords p){service.change(a.getName(),p.currentPassword(),p.newPassword(),p.confirmation());}
    @PostMapping("/auth/recovery-code") public Map<String,String> issue(Authentication a,@Valid @RequestBody Issue p){return Map.of("recoveryCode",service.issue(a.getName(),p.currentPassword()));}
    @PostMapping("/auth/recover") public RecoveryService.Recovered recover(@Valid @RequestBody Recover p){return service.recover(p.email(),p.code(),p.newPassword(),p.confirmation());}
    public record LinkToken(@NotBlank @Size(max=100) String token) {}
    @PostMapping("/auth/recovery-link/status") public RecoveryService.LinkStatus status(@Valid @RequestBody LinkToken p){return service.status(p.token());}
    @PostMapping("/auth/recovery-link") public RecoveryService.Recovered redeem(@Valid @RequestBody Redeem p){return service.redeem(p.token(),p.newPassword(),p.confirmation());}
    @GetMapping("/manager/recovery-accounts") public List<Api.AccountView> targets(Authentication auth) {
        Account manager=accounts.findByEmail(auth.getName()).orElseThrow();
        return java.util.stream.Stream.of(Account.Role.RESIDENT,Account.Role.PROVIDER)
            .flatMap(role->accounts.findByCommunityAndRoleOrderBySubmittedAtDesc(manager.community,role).stream()).map(Api.AccountView::of).toList();
    }
    @PostMapping("/manager/accounts/{id}/recovery") public RecoveryService.IssuedLink create(Authentication a,@PathVariable Long id,@Valid @RequestBody Assistance p){return service.createLink(a.getName(),id,p.note(),p.verified());}
    @DeleteMapping("/manager/accounts/{id}/recovery") public void revoke(Authentication a,@PathVariable Long id){service.revoke(a.getName(),id);}
    @GetMapping("/manager/accounts/{id}/recovery") public List<RecoveryService.LinkView> history(Authentication a,@PathVariable Long id){return service.history(a.getName(),id);}
}
