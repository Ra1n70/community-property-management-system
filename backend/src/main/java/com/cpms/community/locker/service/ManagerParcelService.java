package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.IntakeSource;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.repository.ParcelRepository;
import com.cpms.community.PageView;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cpms.community.locker.enums.CellStatus;
import java.time.Instant;
import java.util.List;
import com.cpms.community.locker.entity.PickupCredential;
import com.cpms.community.locker.enums.PickupCredentialStatus;
import com.cpms.community.locker.repository.PickupCredentialRepository;
@Service
public class ManagerParcelService {
    @org.springframework.beans.factory.annotation.Value("${locker.email-enabled:false}")
    private boolean emailEnabled;

    public record ParcelView(
            Long id,
            String residentName,
            String room,
            String lockerNumber,
            String lockerLocation,
            String cellNumber,
            String carrierName,
            ParcelStatus status,
            IntakeSource intakeSource,
            Long registeredBy,
            String courierName,
            Instant storedAt,
            Instant expiresAt
    ) {}

    private final AccountService accounts;
    private final ParcelRepository parcels;
    private final PickupCredentialRepository credentials;
    private final PickupEmailQueueService emailQueue;
    private final PickupCodeService pickupCodes;
    private final com.cpms.community.AccountRepository residentAccounts;
    public ManagerParcelService(
            AccountService accounts,
            ParcelRepository parcels,
            PickupCredentialRepository credentials,
            PickupEmailQueueService emailQueue,
            PickupCodeService pickupCodes,
            com.cpms.community.AccountRepository residentAccounts
    ) {
        this.accounts = accounts;
        this.parcels = parcels;
        this.credentials = credentials;
        this.emailQueue = emailQueue;
        this.pickupCodes = pickupCodes;
        this.residentAccounts = residentAccounts;
    }

    @Transactional(readOnly = true)
    public List<ParcelView> list(
            String email,
            ParcelStatus status,
            Long lockerId
    ) {
        Account manager = accounts.current(email);

        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN,
                    "Manager access required."
            );
        }

        List<Parcel> found;

        if (status != null && lockerId != null) {
            found = parcels
                    .findByCommunityAndStatusAndCellLockerIdOrderByStoredAtDesc(
                            manager.community, status, lockerId
                    );
        } else if (status != null) {
            found = parcels.findByCommunityAndStatusOrderByStoredAtDesc(
                    manager.community, status
            );
        } else if (lockerId != null) {
            found = parcels
                    .findByCommunityAndCellLockerIdOrderByStoredAtDesc(
                            manager.community, lockerId
                    );
        } else {
            found = parcels.findByCommunityOrderByStoredAtDesc(
                    manager.community
            );
        }

        return found.stream().map(this::toView).toList();
    }

    /** One page of the community's packages, newest first, with the same filters as {@link #list}. */
    @Transactional(readOnly = true)
    public PageView<ParcelView> page(String email, ParcelStatus status, Long lockerId, Integer page, Integer size) {
        Account manager = requireManager(email);
        Specification<Parcel> spec = (p, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> where = new java.util.ArrayList<>();
            where.add(cb.equal(p.get("community"), manager.community));
            if (status != null) where.add(cb.equal(p.get("status"), status));
            if (lockerId != null) where.add(cb.equal(p.get("cell").get("locker").get("id"), lockerId));
            return cb.and(where.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageView.of(parcels.findAll(spec, PageView.request(page, size, NEWEST)), this::toView);
    }

    /** A single package, e.g. one opened from Search that is not on the current page. */
    @Transactional(readOnly = true)
    public ParcelView get(String email, Long parcelId) {
        Account manager = requireManager(email);
        return parcels.findById(parcelId).filter(p -> p.community.equals(manager.community)).map(this::toView)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Parcel not found."));
    }

    public static final Sort NEWEST = Sort.by("storedAt", "id").descending();

    private Account requireManager(String email) {
        Account manager = accounts.current(email);
        if (manager.role != Account.Role.MANAGER)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Manager access required.");
        return manager;
    }

    private ParcelView toView(Parcel parcel) {
        Account resident =
                Hibernate.unproxy(parcel.resident, Account.class);
        LockerCell cell =
                Hibernate.unproxy(parcel.cell, LockerCell.class);
        Locker locker =
                Hibernate.unproxy(cell.locker, Locker.class);

        return new ParcelView(
                parcel.id,
                resident.name,
                resident.room,
                locker.lockerNumber,
                locker.location,
                cell.cellNumber,
                parcel.carrierName,
                parcel.status,
                parcel.intakeSource,
                parcel.registeredBy,
                parcel.courierName,
                parcel.storedAt,
                parcel.expiresAt
        );
    }
    @Transactional
    public ParcelView retrieveExpired(String email, Long parcelId) {
        Account manager = accounts.current(email);
        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN, "Manager access required."
            );
        }

        Parcel locked = parcels.lockByIdAndCommunity(
                parcelId, manager.community
        ).orElseThrow(() -> AccountService.fail(
                HttpStatus.NOT_FOUND, "Parcel not found."
        ));
        Parcel parcel = Hibernate.unproxy(locked, Parcel.class);

        if (parcel.status != ParcelStatus.EXPIRED) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Only expired parcels can be retrieved."
            );
        }

        LockerCell cell = Hibernate.unproxy(
                parcel.cell, LockerCell.class
        );
        if (cell.status != CellStatus.OCCUPIED) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Parcel cell is not occupied."
            );
        }

        parcel.status = ParcelStatus.RETRIEVED;
        parcel.retrievedAt = Instant.now();
        cell.status = CellStatus.AVAILABLE;

        return toView(parcel);
    }
    @Transactional
    public void resendPickupEmail(String email, Long parcelId) {
        if (!emailEnabled) throw AccountService.fail(HttpStatus.GONE, "Email notifications are disabled. Pickup details are available in My Packages.");
        Account manager = accounts.current(email);
        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN, "Manager access required."
            );
        }

        Parcel found = parcels.findById(parcelId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Parcel not found."
                ));
        if (!manager.community.equals(found.community)) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Parcel not found."
            );
        }

        // 先锁凭证，再锁包裹，与取件流程保持一致。
        List<PickupCredential> active = credentials
                .lockByParcelIdAndStatus(
                        parcelId, PickupCredentialStatus.ACTIVE
                );
        Parcel parcel = Hibernate.unproxy(
                parcels.lockByIdAndCommunity(
                        parcelId, manager.community
                ).orElseThrow(),
                Parcel.class
        );

        if (parcel.status != ParcelStatus.PENDING_PICKUP
                || !Instant.now().isBefore(parcel.expiresAt)) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Only unexpired pending parcels can be resent."
            );
        }
        if (active.size() != 1 || active.get(0).codeNonce == null) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "No active pickup code is available."
            );
        }

        emailQueue.scheduleResend(
                Hibernate.unproxy(active.get(0), PickupCredential.class)
        );
    }
    @Transactional
    public void regeneratePickupCode(String email, Long parcelId) {
        Account manager = accounts.current(email);
        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN, "Manager access required."
            );
        }

        Parcel found = parcels.findById(parcelId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Parcel not found."
                ));
        if (!manager.community.equals(found.community)) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Parcel not found."
            );
        }

        // 先锁旧凭证，再锁包裹，避免与取件流程交叉更新。
        List<PickupCredential> active = credentials
                .lockByParcelIdAndStatus(
                        parcelId, PickupCredentialStatus.ACTIVE
                );
        Parcel parcel = Hibernate.unproxy(
                parcels.lockByIdAndCommunity(
                        parcelId, manager.community
                ).orElseThrow(),
                Parcel.class
        );

        if (parcel.status != ParcelStatus.PENDING_PICKUP
                || !Instant.now().isBefore(parcel.expiresAt)) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Only unexpired pending parcels can get a new code."
            );
        }
        if (active.size() != 1) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Exactly one active pickup code is required."
            );
        }

        PickupCodeService.IssuedCode issued =
                pickupCodes.regenerateCode(parcel);

        emailQueue.scheduleRegenerated(
                issued.credential(),
                parcel.storedAt
        );
    }
    public record ResidentOption(Long id, String name, String room) {}

    @Transactional(readOnly = true)
    public List<ResidentOption> searchResidents(String email, String query) {
        Account manager = accounts.current(email);
        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN, "Manager access required."
            );
        }

        String keyword = query == null ? "" : query.strip();
        if (keyword.isEmpty() || keyword.length() > 100) {
            throw AccountService.fail(
                    HttpStatus.BAD_REQUEST,
                    "Enter a name or room, up to 100 characters."
            );
        }

        return residentAccounts.searchResidents(
                        manager.community,
                        Account.Role.RESIDENT,
                        Account.Status.APPROVED,
                        keyword,
                        org.springframework.data.domain.PageRequest.of(0, 20)
                )
                .stream()
                .map(a -> new ResidentOption(a.id, a.name, a.room))
                .toList();
    }
}
