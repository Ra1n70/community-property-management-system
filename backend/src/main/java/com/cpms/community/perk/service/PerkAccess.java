package com.cpms.community.perk.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PerkAccess {
    private final AccountService accounts;

    public PerkAccess(AccountService accounts) {
        this.accounts = accounts;
    }

    public Account requireViewer(String email) {
        Account account = accounts.current(email);
        if (account.status != Account.Status.APPROVED) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Approved account required.");
        }
        if (account.role != Account.Role.RESIDENT && account.role != Account.Role.MANAGER) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Local perks are not available for your role.");
        }
        return account;
    }

    public Account requireManager(String email) {
        Account account = accounts.current(email);
        if (account.status != Account.Status.APPROVED || account.role != Account.Role.MANAGER) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Manager access required.");
        }
        return account;
    }
}
