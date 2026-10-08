package com.cpms.community.support;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Contact & Support: the property office's contact details and a FAQ, shown with Messages.
 * Questions go to the existing direct messages; there is no separate ticket flow.
 */
@RestController
@RequestMapping("/api/support")
@Transactional
public class SupportApi {
    public record Contact(@Size(max = 40) String officePhone, @Size(max = 254) @Email String email, @Size(max = 200) String officeHours,
                          @Size(max = 200) String address, @Size(max = 40) String emergencyPhone, Instant updatedAt, Long version) {
        static Contact of(SupportContact c) {
            return c == null ? new Contact(null, null, null, null, null, null, null)
                    : new Contact(c.officePhone, c.email, c.officeHours, c.address, c.emergencyPhone, c.updatedAt, c.version);
        }
    }
    public record Faq(Long id, @NotBlank @Size(max = 200) String question, @NotBlank @Size(max = 2000) String answer, Long version) {
        static Faq of(SupportFaq f) { return new Faq(f.id, f.question, f.answer, f.version); }
    }
    public record Support(Contact contact, List<Faq> faqs) {}
    public record Order(@NotNull List<Long> ids) {}

    private final AccountService accountService;
    private final SupportContactRepository contacts;
    private final SupportFaqRepository faqs;

    public SupportApi(AccountService accountService, SupportContactRepository contacts, SupportFaqRepository faqs) {
        this.accountService = accountService; this.contacts = contacts; this.faqs = faqs;
    }

    private Account member(Authentication auth) {
        Account a = accountService.current(auth.getName());
        if (a.status != Account.Status.APPROVED || a.role == Account.Role.PROVIDER)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Contact & Support is available to approved residents and managers.");
        return a;
    }

    private Account manager(Authentication auth) {
        Account a = member(auth);
        if (a.role != Account.Role.MANAGER) throw AccountService.fail(HttpStatus.FORBIDDEN, "Manager access required.");
        return a;
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static RuntimeException changed() { return AccountService.fail(HttpStatus.CONFLICT, "This was changed by someone else. Refresh and try again."); }

    @GetMapping
    @Transactional(readOnly = true)
    public Support get(Authentication auth) {
        Account a = member(auth);
        return new Support(Contact.of(contacts.findById(a.community).orElse(null)),
                faqs.findByCommunityOrderByPositionAscIdAsc(a.community).stream().map(Faq::of).toList());
    }

    @PutMapping("/contact")
    public Contact saveContact(Authentication auth, @Valid @RequestBody Contact input) {
        Account a = manager(auth);
        SupportContact c = contacts.findById(a.community).orElse(null);
        if (c == null) { c = new SupportContact(); c.community = a.community; }
        else if (!Objects.equals(c.version, input.version())) throw changed();
        c.officePhone = optional(input.officePhone()); c.email = optional(input.email()); c.officeHours = optional(input.officeHours());
        c.address = optional(input.address()); c.emergencyPhone = optional(input.emergencyPhone()); c.updatedAt = Instant.now();
        return Contact.of(contacts.saveAndFlush(c));
    }

    @PostMapping("/faqs")
    @ResponseStatus(HttpStatus.CREATED)
    public Faq addFaq(Authentication auth, @Valid @RequestBody Faq input) {
        Account a = manager(auth);
        List<SupportFaq> existing = faqs.findByCommunityOrderByPositionAscIdAsc(a.community);
        if (existing.size() >= 50) throw AccountService.fail(HttpStatus.BAD_REQUEST, "A community can have up to 50 questions.");
        SupportFaq f = new SupportFaq();
        f.community = a.community; f.question = input.question().strip(); f.answer = input.answer().strip();
        f.position = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).position + 1;
        return Faq.of(faqs.saveAndFlush(f));
    }

    @PutMapping("/faqs/{id}")
    public Faq updateFaq(Authentication auth, @PathVariable Long id, @Valid @RequestBody Faq input) {
        Account a = manager(auth);
        SupportFaq f = faqs.findByIdAndCommunity(id, a.community).orElseThrow(SupportApi::missing);
        if (!Objects.equals(f.version, input.version())) throw changed();
        f.question = input.question().strip(); f.answer = input.answer().strip(); f.updatedAt = Instant.now();
        return Faq.of(faqs.saveAndFlush(f));
    }

    /** Saves a new order for the community's questions; ids must list every question once. */
    @PutMapping("/faqs/order")
    public List<Faq> reorder(Authentication auth, @Valid @RequestBody Order input) {
        Account a = manager(auth);
        List<SupportFaq> existing = faqs.findByCommunityOrderByPositionAscIdAsc(a.community);
        if (input.ids().size() != existing.size() || !existing.stream().map(f -> f.id).toList().containsAll(input.ids())) throw changed();
        for (SupportFaq f : existing) f.position = input.ids().indexOf(f.id);
        faqs.saveAllAndFlush(existing);
        return faqs.findByCommunityOrderByPositionAscIdAsc(a.community).stream().map(Faq::of).toList();
    }

    @DeleteMapping("/faqs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFaq(Authentication auth, @PathVariable Long id) {
        Account a = manager(auth);
        faqs.delete(faqs.findByIdAndCommunity(id, a.community).orElseThrow(SupportApi::missing));
    }

    private static RuntimeException missing() { return AccountService.fail(HttpStatus.NOT_FOUND, "Question not found."); }
}
