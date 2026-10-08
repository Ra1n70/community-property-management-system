package com.cpms.community.perk.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.perk.entity.PerkEntity;
import com.cpms.community.perk.model.PerkDto;
import com.cpms.community.perk.repository.PerkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

@Service
public class PerkService {
    private static final List<String> STATES = List.of("ACTIVE", "UPCOMING", "EXPIRED", "ALL");

    private final PerkRepository perks;
    private final PerkAccess access;

    public PerkService(PerkRepository perks, PerkAccess access) {
        this.perks = perks;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<PerkDto.Summary> list(String email, String category, String status, String sort) {
        Account viewer = access.requireViewer(email);
        Instant now = Instant.now();
        PerkDto.Category categoryFilter = parseCategory(category);
        Predicate<PerkEntity> stateFilter = stateFilter(status, "ACTIVE", now);
        return perks.findByCommunityAndPublishedTrueOrderByCreatedAtDesc(viewer.community).stream()
                .filter(perk -> categoryFilter == null || perk.category.equals(categoryFilter.name()))
                .filter(stateFilter)
                .sorted(listComparator(sort))
                .map(perk -> toSummary(perk, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public PerkDto.Detail detail(String email, Long id) {
        Account viewer = access.requireViewer(email);
        PerkEntity perk = perks.findByIdAndCommunity(id, viewer.community).orElseThrow(this::notFound);
        if (!perk.published) {
            throw notFound();
        }
        return toDetail(perk, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<PerkDto.Summary> managerList(String email, String category, String status, String sort) {
        Account manager = access.requireManager(email);
        Instant now = Instant.now();
        PerkDto.Category categoryFilter = parseCategory(category);
        Predicate<PerkEntity> stateFilter = stateFilter(status, "ALL", now);
        return perks.findByCommunityOrderByCreatedAtDesc(manager.community).stream()
                .filter(perk -> categoryFilter == null || perk.category.equals(categoryFilter.name()))
                .filter(stateFilter)
                .sorted(listComparator(sort))
                .map(perk -> toSummary(perk, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public PerkDto.Detail managerDetail(String email, Long id) {
        Account manager = access.requireManager(email);
        PerkEntity perk = perks.findByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        return toDetail(perk, Instant.now());
    }

    @Transactional
    public PerkDto.Detail create(String email, PerkDto.SaveRequest request) {
        Account manager = access.requireManager(email);
        Instant now = Instant.now();
        validateWindow(request);
        PerkEntity perk = new PerkEntity();
        perk.community = manager.community;
        perk.createdById = manager.id;
        perk.published = true;
        applyRequest(perk, request);
        perk.createdAt = now;
        perk.updatedAt = now;
        perks.saveAndFlush(perk);
        return toDetail(perk, now);
    }

    @Transactional
    public PerkDto.Detail update(String email, Long id, PerkDto.SaveRequest request) {
        Account manager = access.requireManager(email);
        PerkEntity perk = perks.lockByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        validateWindow(request);
        applyRequest(perk, request);
        perk.updatedAt = Instant.now();
        return toDetail(perk, Instant.now());
    }

    @Transactional
    public void setPublished(String email, Long id, boolean published) {
        Account manager = access.requireManager(email);
        PerkEntity perk = perks.lockByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        perk.published = published;
        perk.updatedAt = Instant.now();
    }

    @Transactional
    public void delete(String email, Long id) {
        Account manager = access.requireManager(email);
        PerkEntity perk = perks.lockByIdAndCommunity(id, manager.community).orElseThrow(this::notFound);
        perks.delete(perk);
    }

    private void applyRequest(PerkEntity perk, PerkDto.SaveRequest request) {
        perk.businessName = request.businessName().strip();
        perk.title = request.title().strip();
        perk.description = request.description().strip();
        perk.category = request.category().name();
        perk.contact = optional(request.contact());
        perk.address = optional(request.address());
        perk.website = optional(request.website());
        perk.startAt = request.startAt();
        perk.endAt = request.endAt();
    }

    private void validateWindow(PerkDto.SaveRequest request) {
        if (request.startAt() != null && !request.endAt().isAfter(request.startAt())) {
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "End date must be after the start date.");
        }
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private PerkDto.Category parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return PerkDto.Category.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid category.");
        }
    }

    private Predicate<PerkEntity> stateFilter(String status, String fallback, Instant now) {
        String wanted = (status == null || status.isBlank() ? fallback : status).trim().toUpperCase();
        if (!STATES.contains(wanted)) {
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid status.");
        }
        if (wanted.equals("ALL")) {
            return perk -> true;
        }
        return perk -> state(perk, now).equals(wanted);
    }

    private String state(PerkEntity perk, Instant now) {
        if (now.isAfter(perk.endAt)) {
            return "EXPIRED";
        }
        if (perk.startAt != null && now.isBefore(perk.startAt)) {
            return "UPCOMING";
        }
        return "ACTIVE";
    }

    private Comparator<PerkEntity> listComparator(String sort) {
        if ("ending".equalsIgnoreCase(sort)) {
            return Comparator.comparing((PerkEntity perk) -> perk.endAt)
                    .thenComparing(perk -> perk.id, Comparator.reverseOrder());
        }
        return Comparator.comparing((PerkEntity perk) -> perk.createdAt, Comparator.reverseOrder())
                .thenComparing(perk -> perk.id, Comparator.reverseOrder());
    }

    private PerkDto.Summary toSummary(PerkEntity perk, Instant now) {
        return new PerkDto.Summary(
                perk.id,
                perk.businessName,
                perk.title,
                perk.category,
                perk.startAt,
                perk.endAt,
                state(perk, now).equals("ACTIVE"),
                perk.published);
    }

    private PerkDto.Detail toDetail(PerkEntity perk, Instant now) {
        return new PerkDto.Detail(
                perk.id,
                perk.community,
                perk.businessName,
                perk.title,
                perk.description,
                perk.category,
                perk.contact,
                perk.address,
                perk.website,
                perk.startAt,
                perk.endAt,
                state(perk, now).equals("ACTIVE"),
                perk.published,
                perk.createdById,
                perk.createdAt,
                perk.updatedAt);
    }

    private RuntimeException notFound() {
        return AccountService.fail(HttpStatus.NOT_FOUND, "Local perk not found.");
    }
}
