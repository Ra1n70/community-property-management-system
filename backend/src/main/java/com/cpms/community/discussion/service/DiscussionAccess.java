package com.cpms.community.discussion.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DiscussionAccess {
    private final AccountService accounts;

    public DiscussionAccess(AccountService accounts) {
        this.accounts = accounts;
    }

    public Account requireParticipant(String email) {
        Account account = accounts.current(email);
        if (account.status != Account.Status.APPROVED) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Approved account required.");
        }
        if (account.role != Account.Role.RESIDENT && account.role != Account.Role.MANAGER) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Discussion board is not available for your role.");
        }
        return account;
    }

    public Account requireApprovedResident(String email) {
        Account account = requireParticipant(email);
        if (account.role != Account.Role.RESIDENT) {
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Only residents can perform this action.");
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
