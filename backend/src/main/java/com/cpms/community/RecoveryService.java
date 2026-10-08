package com.cpms.community;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static com.cpms.community.AccountService.fail;

@Service
public class RecoveryService {
    private final AccountRepository accounts;
    private final RecoveryLinkRepository links;
    private final PasswordEncoder encoder;
    private final SecureRandom random=new SecureRandom();
    public RecoveryService(AccountRepository accounts, RecoveryLinkRepository links, PasswordEncoder encoder) {
        this.accounts=accounts;this.links=links;this.encoder=encoder;
    }
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String token() {byte[] bytes=new byte[24];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private Account locked(String email) {
        Account a=accounts.findByEmail(AccountService.normalize(email)).orElseThrow(()->fail(HttpStatus.UNAUTHORIZED,"Please sign in again."));
        return accounts.lockById(a.id).orElseThrow();
    }
    private void password(Account a,String next,String confirmation) {
        if(next==null || next.length()<10 || next.length()>64 || next.getBytes(StandardCharsets.UTF_8).length>72 || !next.equals(confirmation))
            throw fail(HttpStatus.BAD_REQUEST,"Use matching passwords of 10–64 characters (up to 72 UTF-8 bytes).");
        if(encoder.matches(next,a.passwordHash))throw fail(HttpStatus.BAD_REQUEST,"Choose a different password.");
    }
    private String rotate(Account a) {String code=token();a.recoveryHash=hash(code);return code;}
    private void revokeLinks(Long id) {
        for(RecoveryLink l:links.findByAccountIdOrderByCreatedAtDesc(id))if(l.usedAt==null && l.revokedAt==null)l.revokedAt=Instant.now();
    }
    private void updatePassword(Account a,String next) {
        a.passwordHash=encoder.encode(next);a.sessionVersion=UUID.randomUUID().toString();revokeLinks(a.id);
    }
    @Transactional
    public String issue(String email,String currentPassword) {
        Account a=locked(email);
        if(a.recoveryHash!=null && (currentPassword==null || !encoder.matches(currentPassword,a.passwordHash)))
            throw fail(HttpStatus.BAD_REQUEST,"Current password is incorrect.");
        revokeLinks(a.id);return rotate(a);
    }
    @Transactional
    public void change(String email,String old,String next,String confirmation) {
        Account a=locked(email);
        if(old==null || !encoder.matches(old,a.passwordHash))throw fail(HttpStatus.BAD_REQUEST,"Current password is incorrect.");
        password(a,next,confirmation);updatePassword(a,next);
    }
    @Transactional
    public Recovered recover(String email,String code,String next,String confirmation) {
        Account found=accounts.findByEmail(AccountService.normalize(email)).orElseThrow(()->fail(HttpStatus.BAD_REQUEST,"Email or recovery code is incorrect."));
        Account a=accounts.lockById(found.id).orElseThrow();
        if(code==null || a.recoveryHash==null || !MessageDigest.isEqual(hash(code.strip()).getBytes(StandardCharsets.US_ASCII),a.recoveryHash.getBytes(StandardCharsets.US_ASCII)))
            throw fail(HttpStatus.BAD_REQUEST,"Email or recovery code is incorrect.");
        password(a,next,confirmation);updatePassword(a,next);return new Recovered(rotate(a),a.role);
    }
    private Account managed(String email,Long id) {
        Account manager=accounts.findByEmail(AccountService.normalize(email)).orElseThrow();
        Account target=accounts.lockById(id).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"Account not found."));
        if(manager.role!=Account.Role.MANAGER || target.role==Account.Role.MANAGER || !Objects.equals(target.community,manager.community))
            throw fail(HttpStatus.NOT_FOUND,"Account not found.");
        return target;
    }
    public record IssuedLink(String token,Instant expiresAt) {}
    public record Recovered(String recoveryCode,Account.Role role) {}
    @Transactional
    public IssuedLink createLink(String email,Long id,String note,boolean verified) {
        Account a=managed(email,id);
        if(!verified || note==null || note.isBlank() || note.length()>1000)throw fail(HttpStatus.BAD_REQUEST,"Confirm identity and enter verification notes.");
        revokeLinks(a.id);
        String token=token();RecoveryLink l=new RecoveryLink();l.accountId=a.id;
        l.managerId=accounts.findByEmail(AccountService.normalize(email)).orElseThrow().id;l.tokenHash=hash(token);
        l.verificationNote=note.strip();l.expiresAt=Instant.now().plusSeconds(1800);links.save(l);
        return new IssuedLink(token,l.expiresAt);
    }
    @Transactional
    public void revoke(String email,Long id) {managed(email,id);revokeLinks(id);}
    public record LinkView(Long id,Long managerId,String verificationNote,Instant createdAt,Instant expiresAt,String status) {}
    @Transactional
    public List<LinkView> history(String email,Long id) {
        managed(email,id);
        return links.findByAccountIdOrderByCreatedAtDesc(id).stream().map(l->new LinkView(l.id,l.managerId,l.verificationNote,l.createdAt,l.expiresAt,
            l.usedAt!=null?"COMPLETED":l.revokedAt!=null?"REVOKED":l.expiresAt.isAfter(Instant.now())?"PENDING":"EXPIRED")).toList();
    }
    public record LinkStatus(boolean valid,Instant expiresAt) {}
    /** Lets the reset page reject replaced, revoked, used or expired links before a new password is typed. */
    @Transactional(readOnly=true)
    public LinkStatus status(String token) {
        return links.findByTokenHash(hash(token.strip())).filter(l->l.usedAt==null && l.revokedAt==null && l.expiresAt.isAfter(Instant.now()))
            .map(l->new LinkStatus(true,l.expiresAt)).orElse(new LinkStatus(false,null));
    }
    @Transactional
    public Recovered redeem(String token,String next,String confirmation) {
        RecoveryLink l=links.findByTokenHash(hash(token)).orElseThrow(()->fail(HttpStatus.BAD_REQUEST,"Recovery link is invalid or expired."));
        Account a=accounts.lockById(l.accountId).orElseThrow();
        // Refresh after acquiring the account lock: another request may have consumed/revoked this link.
        links.flush();
        entityManager.refresh(l);
        if(l.usedAt!=null || l.revokedAt!=null || !l.expiresAt.isAfter(Instant.now()))throw fail(HttpStatus.BAD_REQUEST,"Recovery link is invalid or expired.");
        password(a,next,confirmation);l.usedAt=Instant.now();updatePassword(a,next);return new Recovered(rotate(a),a.role);
    }
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
}
