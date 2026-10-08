package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import com.cpms.community.AttemptLimiter;
import com.cpms.community.locker.entity.Courier;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.IntakeSource;
import com.cpms.community.locker.repository.CourierRepository;
import com.cpms.community.locker.repository.ParcelRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Personal kiosk store codes for couriers. A delivery company manages its own couriers;
 * the property manager manages independent couriers and can review every courier in the community.
 */
@Service
@Transactional
public class CourierService {
    /** carrier and temporary are only used by the manager for walk-in couriers without a company account. */
    public record Input(@NotBlank @Size(max = 100) String name, @Size(max = 30) String phone,
                        @Size(max = 100) String carrier, boolean temporary) {}
    /** Contact details only; the store code and temporary status do not change. carrier is manager-only. */
    public record Update(@NotBlank @Size(max = 100) String name, @Size(max = 30) String phone,
                         @Size(max = 100) String carrier) {}
    public record CourierView(Long id, String name, String phone, Long companyId, String companyName,
                              boolean active, String codeHint, Instant createdAt, boolean manageable,
                              Instant expiresAt, String storeCode) {}
    public record IssuedCourier(CourierView courier, String storeCode) {}
    public record KioskCourier(Long id, String name, String companyName) {}
    public record DeliveryView(Long id, String carrierName, String courierName, String lockerLocation,
                               String cellNumber, String status, Instant storedAt, Instant pickedUpAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final CourierRepository couriers;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final PickupCodeService codes;
    private final ParcelRepository parcels;
    private final AttemptLimiter limiter;

    public CourierService(CourierRepository couriers, AccountService accountService, AccountRepository accounts,
                          PickupCodeService codes, ParcelRepository parcels, AttemptLimiter limiter) {
        this.couriers = couriers;
        this.accountService = accountService;
        this.accounts = accounts;
        this.codes = codes;
        this.parcels = parcels;
        this.limiter = limiter;
    }

    private static boolean company(Account a) {
        return a.role == Account.Role.PROVIDER && a.providerType == Account.ProviderType.DELIVERY;
    }

    private Account actor(String email) {
        Account a = accountService.current(email);
        if (a.status != Account.Status.APPROVED || (a.role != Account.Role.MANAGER && !company(a)))
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Delivery company or manager access required.");
        return a;
    }

    private boolean manages(Account a, Courier c) {
        return company(a) ? a.id.equals(c.companyId) : c.companyId == null;
    }

    private CourierView view(Account viewer, Courier c) {
        return new CourierView(c.id, c.name, c.phone, c.companyId, c.companyName, c.active, c.codeHint, c.createdAt,
                manages(viewer, c), c.expiresAt, manages(viewer, c) ? code(c) : null);
    }

    private Courier owned(Account a, Long id) {
        Courier c = couriers.findByIdAndCommunity(id, a.community)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Courier not found."));
        if (company(a) && !a.id.equals(c.companyId)) throw AccountService.fail(HttpStatus.NOT_FOUND, "Courier not found.");
        if (!manages(a, c))
            throw AccountService.fail(HttpStatus.FORBIDDEN, "This courier's delivery company manages their store code.");
        return c;
    }

    private String hash(String code) { return codes.hash("courier-store-code:" + code); }

    private String derive(String nonce) {
        long value = Long.parseLong(codes.hash("courier-store-code-seed:" + nonce).substring(0, 12), 16);
        return String.format("%08d", value % 100_000_000L);
    }

    /** The courier's current code, or null for codes issued before seeds were stored (reset to view them). */
    private String code(Courier c) {
        if (c.codeNonce == null) return null;
        String code = derive(c.codeNonce);
        return hash(code).equals(c.codeHash) ? code : null;
    }

    private String issue(Courier c) {
        String nonce, code;
        do {
            byte[] seed = new byte[18];
            RANDOM.nextBytes(seed);
            nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(seed);
            code = derive(nonce);
        } while (couriers.existsByCodeHash(hash(code)));
        c.codeNonce = nonce;
        c.codeHash = hash(code);
        c.codeHint = code.substring(4);
        c.updatedAt = Instant.now();
        return code;
    }

    /** Temporary codes work until the end of the current day in community time (Pacific). */
    private static Instant endOfToday() {
        java.time.ZoneId zone = java.time.ZoneId.of("America/Los_Angeles");
        return java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    }

    @Transactional(readOnly = true)
    public List<CourierView> list(String email) {
        Account a = actor(email);
        List<Courier> rows = company(a) ? couriers.findByCompanyIdOrderByCreatedAtDesc(a.id)
                : couriers.findByCommunityOrderByCreatedAtDesc(a.community);
        return rows.stream().map(c -> view(a, c)).toList();
    }

    public IssuedCourier create(String email, Input input) {
        Account a = actor(email);
        if (company(a) && (input.temporary() || (input.carrier() != null && !input.carrier().isBlank())))
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Only property management can issue temporary codes.");
        Courier c = new Courier();
        c.community = a.community;
        c.companyId = company(a) ? a.id : null;
        c.companyName = company(a) ? a.name
                : input.carrier() == null || input.carrier().isBlank() ? null : input.carrier().strip();
        if (input.temporary()) c.expiresAt = endOfToday();
        c.name = input.name().strip();
        c.phone = input.phone() == null || input.phone().isBlank() ? null : input.phone().strip();
        c.createdBy = a.id;
        String code = issue(c);
        couriers.saveAndFlush(c);
        return new IssuedCourier(view(a, c), code);
    }

    public IssuedCourier resetCode(String email, Long id) {
        Account a = actor(email);
        Courier c = owned(a, id);
        if (c.expiresAt != null) c.expiresAt = endOfToday();
        String code = issue(c);
        couriers.saveAndFlush(c);
        return new IssuedCourier(view(a, c), code);
    }

    /** Packages already stored keep the courier name recorded at delivery. */
    public CourierView update(String email, Long id, Update input) {
        Account a = actor(email);
        Courier c = owned(a, id);
        boolean carrier = input.carrier() != null && !input.carrier().isBlank();
        if (company(a) && carrier)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Your couriers always use your company name.");
        c.name = input.name().strip();
        c.phone = input.phone() == null || input.phone().isBlank() ? null : input.phone().strip();
        if (!company(a)) c.companyName = carrier ? input.carrier().strip() : null;
        c.updatedAt = Instant.now();
        return view(a, couriers.saveAndFlush(c));
    }

    public CourierView setActive(String email, Long id, boolean active) {
        Account a = actor(email);
        Courier c = owned(a, id);
        c.active = active;
        c.updatedAt = Instant.now();
        return view(a, couriers.saveAndFlush(c));
    }

    /** Validates a kiosk store code for this locker; repeated failures from one client are rate limited. */
    @Transactional(readOnly = true)
    public Courier authenticate(String code, Locker locker, String clientKey) {
        Courier c = code == null || !code.strip().matches("\\d{8}") ? null
                : couriers.findByCodeHash(hash(code.strip())).orElse(null);
        boolean valid = c != null && c.active && c.community.equals(locker.community)
                && (c.expiresAt == null || Instant.now().isBefore(c.expiresAt))
                && (c.companyId == null || accounts.findById(c.companyId)
                .filter(owner -> owner.status == Account.Status.APPROVED && company(owner)
                        && owner.community.equals(c.community)).isPresent());
        if (!valid) {
            if (!limiter.allow("courier-store-code:" + clientKey, 10))
                throw AccountService.fail(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in 15 minutes.");
            throw AccountService.fail(HttpStatus.UNAUTHORIZED, "This store code is not valid at this locker.");
        }
        return c;
    }

    public static KioskCourier kiosk(Courier c) { return new KioskCourier(c.id, c.name, c.companyName); }

    /** Packages stored by this company's couriers, without resident details or pickup codes. */
    @Transactional(readOnly = true)
    public List<DeliveryView> deliveries(String email) {
        Account a = actor(email);
        if (!company(a)) throw AccountService.fail(HttpStatus.FORBIDDEN, "Delivery company access required.");
        return parcels.findByRegisteredByAndIntakeSourceOrderByStoredAtDesc(a.id, IntakeSource.CARRIER_SELF_SERVICE).stream()
                .map(CourierService::delivery).toList();
    }

    /** One page of {@link #deliveries}, newest first. */
    @Transactional(readOnly = true)
    public com.cpms.community.PageView<DeliveryView> deliveries(String email, Integer page, Integer size) {
        Account a = actor(email);
        if (!company(a)) throw AccountService.fail(HttpStatus.FORBIDDEN, "Delivery company access required.");
        org.springframework.data.jpa.domain.Specification<com.cpms.community.locker.entity.Parcel> spec = (p, q, cb) ->
                cb.and(cb.equal(p.get("registeredBy"), a.id), cb.equal(p.get("intakeSource"), IntakeSource.CARRIER_SELF_SERVICE));
        return com.cpms.community.PageView.of(
                parcels.findAll(spec, com.cpms.community.PageView.request(page, size, ManagerParcelService.NEWEST)),
                CourierService::delivery);
    }

    private static DeliveryView delivery(com.cpms.community.locker.entity.Parcel p) {
        LockerCell cell = Hibernate.unproxy(p.cell, LockerCell.class);
        Locker locker = Hibernate.unproxy(cell.locker, Locker.class);
        return new DeliveryView(p.id, p.carrierName, p.courierName, locker.location, cell.cellNumber,
                p.status.name(), p.storedAt, p.pickedUpAt);
    }
}
