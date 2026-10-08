package com.cpms.community;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.Locale;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final ReviewRepository reviews;
    private final PasswordEncoder encoder;
    private final String invite;
    private final CommunitySettingRepository settings;
    public AccountService(AccountRepository accounts, ReviewRepository reviews, PasswordEncoder encoder,
                          @Value("${demo.invite-code}") String invite, CommunitySettingRepository settings) {
        this.settings=settings;
        this.accounts = accounts; this.reviews = reviews; this.encoder = encoder; this.invite = invite;
    }
    public static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    public Account current(String email) {
        return accounts.findByEmail(normalize(email)).orElseThrow(() -> fail(HttpStatus.UNAUTHORIZED, "Please sign in again."));
    }
    public static ResponseStatusException fail(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    @Transactional
    public Account register(Api.Register input) {
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw fail(HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes.");
        String currentInvite=settings.findById("Demo Community").map(s->s.inviteCode).orElse(invite);
        if (currentInvite.isBlank() || !currentInvite.equals(input.inviteCode())) throw fail(HttpStatus.BAD_REQUEST, "Invalid community code.");
        if (accounts.findByEmail(normalize(input.email())).isPresent()) throw fail(HttpStatus.CONFLICT, "Email is already registered.");
        Account a = new Account(); a.name = input.name().strip(); a.email = normalize(input.email());
        a.passwordHash = encoder.encode(input.password()); a.room = input.room().strip(); a.community = "Demo Community";
        a.role = Account.Role.RESIDENT; a.status = Account.Status.PENDING;
        return accounts.saveAndFlush(a);
    }
    @Transactional
    public Account resubmit(String email, Api.Resubmit input) {
        Account a = accounts.lockById(current(email).id).orElseThrow();
        if (a.role != Account.Role.RESIDENT || a.status != Account.Status.REJECTED)
            throw fail(HttpStatus.CONFLICT, "Only rejected applications can be resubmitted.");
        a.name = input.name().strip(); a.room = input.room().strip(); a.status = Account.Status.PENDING;
        a.rejectionReason = null; a.reviewedAt = null; a.reviewedBy = null; a.submittedAt = Instant.now();
        return a;
    }
    @Transactional
    public Account review(String email, Long id, Api.Decision input) {
        Account manager = current(email);
        if (manager.role != Account.Role.MANAGER) throw fail(HttpStatus.FORBIDDEN, "Manager access required.");
        Account a = accounts.lockById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Application not found."));
        if (!a.community.equals(manager.community) || a.role != Account.Role.RESIDENT)
            throw fail(HttpStatus.NOT_FOUND, "Application not found.");
        if (a.status != Account.Status.PENDING) throw fail(HttpStatus.CONFLICT, "Already reviewed. Refresh the list.");
        if (input.status() != Account.Status.APPROVED && input.status() != Account.Status.REJECTED)
            throw fail(HttpStatus.BAD_REQUEST, "Choose APPROVED or REJECTED.");
        if (input.status() == Account.Status.REJECTED && (input.reason() == null || input.reason().isBlank()))
            throw fail(HttpStatus.BAD_REQUEST, "A rejection reason is required.");
        a.status = input.status(); a.rejectionReason = a.status == Account.Status.REJECTED ? input.reason().strip() : null;
        a.reviewedBy = manager.id; a.reviewedAt = Instant.now();
        ReviewEvent event = new ReviewEvent(); event.accountId = a.id; event.reviewerId = manager.id;
        event.decision = a.status.name(); event.reason = a.rejectionReason; reviews.save(event);
        return a;
    }
}
