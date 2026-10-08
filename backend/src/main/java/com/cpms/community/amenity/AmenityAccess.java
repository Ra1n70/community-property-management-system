package com.cpms.community.amenity;
import com.cpms.community.*;
import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
@Component
public class AmenityAccess {
    private final AccountService accounts;
    public AmenityAccess(AccountService accounts) { this.accounts=accounts; }
    public Account require(Authentication auth, Account.Role role) {
        if(auth==null) throw AccountService.fail(HttpStatus.UNAUTHORIZED,"Please sign in.");
        Account account=accounts.current(auth.getName());
        if(account.role!=role || account.status!=Account.Status.APPROVED)
            throw AccountService.fail(HttpStatus.FORBIDDEN,"Approved " + role.name().toLowerCase() + " access required.");
        return account;
    }
}
