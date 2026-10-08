package com.cpms.community.poll;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Community voting. Managers create, close and delete votes; approved residents vote once per poll.
 * Residents see the results after voting or once the vote has closed; managers always see them.
 */
@RestController
@RequestMapping("/api/polls")
@Transactional
public class PollApi {
    public record Create(@NotBlank @Size(max = 160) String title, @Size(max = 2000) String description,
                         @NotNull @Size(min = 2, max = 10) List<@NotBlank @Size(max = 120) String> options,
                         @NotNull @Future Instant closesAt) {}
    public record Vote(@NotNull Long optionId) {}
    public record OptionView(Long id, String label, Long votes) {}
    public record PollView(Long id, String title, String description, String createdByName, Instant createdAt,
                           Instant closesAt, Instant closedAt, boolean open, boolean resultsVisible, Long totalVotes,
                           Long myOptionId, long eligibleVoters, List<OptionView> options) {}

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final PollRepository polls;
    private final PollVoteRepository votes;

    public PollApi(AccountService accountService, AccountRepository accounts, PollRepository polls, PollVoteRepository votes) {
        this.accountService = accountService; this.accounts = accounts; this.polls = polls; this.votes = votes;
    }

    private Account member(Authentication auth) {
        Account a = accountService.current(auth.getName());
        if (a.status != Account.Status.APPROVED || a.role == Account.Role.PROVIDER)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Voting is available to approved residents and managers.");
        return a;
    }

    private Account manager(Authentication auth) {
        Account a = member(auth);
        if (a.role != Account.Role.MANAGER) throw AccountService.fail(HttpStatus.FORBIDDEN, "Manager access required.");
        return a;
    }

    private static RuntimeException missing() { return AccountService.fail(HttpStatus.NOT_FOUND, "Vote not found."); }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PollView> list(Authentication auth) {
        Account a = member(auth);
        return views(a, polls.findWithOptions(a.community));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PollView create(Authentication auth, @Valid @RequestBody Create input) {
        Account a = manager(auth);
        Instant now = Instant.now();
        if (input.closesAt().isBefore(now.plus(Duration.ofMinutes(5))) || input.closesAt().isAfter(now.plus(Duration.ofDays(366))))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Choose a closing time between 5 minutes and one year from now.");
        Set<String> seen = new HashSet<>();
        Poll p = new Poll();
        p.community = a.community; p.title = input.title().strip();
        p.description = input.description() == null || input.description().isBlank() ? null : input.description().strip();
        p.createdBy = a.id; p.createdByName = a.name; p.closesAt = input.closesAt();
        for (String label : input.options()) {
            String clean = label.strip();
            if (!seen.add(clean.toLowerCase(Locale.ROOT))) throw AccountService.fail(HttpStatus.BAD_REQUEST, "Each option must be different.");
            PollOption o = new PollOption();
            o.poll = p; o.label = clean; o.position = p.options.size();
            p.options.add(o);
        }
        return views(a, List.of(polls.saveAndFlush(p))).get(0);
    }

    @PostMapping("/{id}/vote")
    public PollView vote(Authentication auth, @PathVariable Long id, @Valid @RequestBody Vote input) {
        Account a = member(auth);
        if (a.role != Account.Role.RESIDENT) throw AccountService.fail(HttpStatus.FORBIDDEN, "Only residents can vote.");
        // Locking the poll orders a vote against a manager closing it and against the same resident voting twice.
        Poll p = polls.lock(id, a.community).orElseThrow(PollApi::missing);
        if (!p.open(Instant.now())) throw AccountService.fail(HttpStatus.CONFLICT, "This vote has closed.");
        if (p.options.stream().noneMatch(o -> o.id.equals(input.optionId())))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Choose one of this vote's options.");
        if (votes.existsByPollIdAndResidentId(p.id, a.id)) throw AccountService.fail(HttpStatus.CONFLICT, "You have already voted.");
        PollVote v = new PollVote();
        v.pollId = p.id; v.optionId = input.optionId(); v.residentId = a.id;
        try { votes.saveAndFlush(v); }
        catch (DataIntegrityViolationException e) { throw AccountService.fail(HttpStatus.CONFLICT, "You have already voted."); }
        return views(a, List.of(p)).get(0);
    }

    @PostMapping("/{id}/close")
    public PollView close(Authentication auth, @PathVariable Long id) {
        Account a = manager(auth);
        Poll p = polls.lock(id, a.community).orElseThrow(PollApi::missing);
        if (p.open(Instant.now())) { p.closedAt = Instant.now(); polls.saveAndFlush(p); }
        return views(a, List.of(p)).get(0);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable Long id) {
        Account a = manager(auth);
        Poll p = polls.lock(id, a.community).orElseThrow(PollApi::missing);
        votes.deleteByPollId(p.id);
        polls.delete(p);
    }

    private List<PollView> views(Account viewer, List<Poll> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(p -> p.id).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : votes.countByOption(ids)) counts.put((Long) row[0], (Long) row[1]);
        Map<Long, Long> mine = new HashMap<>();
        if (viewer.role == Account.Role.RESIDENT)
            for (PollVote v : votes.findMine(ids, viewer.id)) mine.put(v.pollId, v.optionId);
        long eligible = accounts.countByCommunityAndRoleAndStatus(viewer.community, Account.Role.RESIDENT, Account.Status.APPROVED);
        Instant now = Instant.now();
        return list.stream().map(p -> {
            boolean open = p.open(now);
            boolean visible = viewer.role == Account.Role.MANAGER || !open || mine.containsKey(p.id);
            long total = p.options.stream().mapToLong(o -> counts.getOrDefault(o.id, 0L)).sum();
            List<OptionView> options = p.options.stream()
                    .map(o -> new OptionView(o.id, o.label, visible ? counts.getOrDefault(o.id, 0L) : null)).toList();
            return new PollView(p.id, p.title, p.description, p.createdByName, p.createdAt, p.closesAt, p.closedAt, open,
                    visible, visible ? total : null, mine.get(p.id), eligible, options);
        }).toList();
    }
}
