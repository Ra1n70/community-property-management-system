package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.repository.ParcelRepository;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cpms.community.locker.entity.PickupCredential;
import com.cpms.community.locker.enums.PickupCredentialStatus;
import com.cpms.community.locker.repository.PickupCredentialRepository;
import java.time.Instant;
import java.util.List;

@Service
public class ResidentParcelService {

    public record ParcelView(
            Long id,
            String carrierName,
            String trackingNumber,
            String lockerNumber,
            String lockerLocation,
            String cellNumber,
            ParcelStatus status,
            Instant storedAt,
            Instant expiresAt,
            Instant pickedUpAt
    ) {}

    private final AccountService accounts;
    private final ParcelRepository parcels;

    private final PickupCredentialRepository credentials;
    private final PickupCodeService pickupCodes;
    private final QrCodeService qrCodes;
    public ResidentParcelService(
            AccountService accounts,
            ParcelRepository parcels,
            PickupCredentialRepository credentials,
            PickupCodeService pickupCodes,
            QrCodeService qrCodes
    ) {
        this.accounts = accounts;
        this.parcels = parcels;
        this.credentials = credentials;
        this.pickupCodes = pickupCodes;
        this.qrCodes = qrCodes;
    }

    private Account requireApprovedResident(String email) {
        Account resident = accounts.current(email);

        if (resident.role != Account.Role.RESIDENT
                || resident.status != Account.Status.APPROVED) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN,
                    "Resident approval is required."
            );
        }

        return resident;
    }

    @Transactional(readOnly = true)
    public List<ParcelView> listMine(String email) {
        Account resident = requireApprovedResident(email);

        return parcels.findByResidentIdOrderByStoredAtDesc(resident.id)
                .stream()
                .map(this::toView)
                .toList();
    }

    /** One page of {@link #listMine}, newest first. */
    @Transactional(readOnly = true)
    public com.cpms.community.PageView<ParcelView> pageMine(String email, Integer page, Integer size) {
        Account resident = requireApprovedResident(email);
        org.springframework.data.jpa.domain.Specification<Parcel> spec =
                (p, q, cb) -> cb.equal(p.get("resident").get("id"), resident.id);
        return com.cpms.community.PageView.of(
                parcels.findAll(spec, com.cpms.community.PageView.request(page, size, ManagerParcelService.NEWEST)),
                this::toView);
    }

    @Transactional(readOnly = true)
    public ParcelDetailView getMine(String email, Long parcelId) {
        Account resident = requireApprovedResident(email);

        Parcel parcel = parcels.findByIdAndResidentId(
                parcelId,
                resident.id
        ).orElseThrow(() -> AccountService.fail(
                HttpStatus.NOT_FOUND,
                "Parcel not found."
        ));

        String code = null;

        if (parcel.status == ParcelStatus.PENDING_PICKUP
                && Instant.now().isBefore(parcel.expiresAt)) {
            PickupCredential credential = credentials
                    .findFirstByParcelIdAndStatusOrderByCreatedAtDesc(
                            parcel.id,
                            PickupCredentialStatus.ACTIVE
                    )
                    .orElse(null);

            if (credential != null && credential.codeNonce != null) {
                code = pickupCodes.codeForDelivery(credential);
            }
        }

        return new ParcelDetailView(
                toView(parcel),
                code,
                code == null ? null : qrCodes.dataUrl(code)
        );
    }

    private ParcelView toView(Parcel parcel) {
        LockerCell cell =
                Hibernate.unproxy(parcel.cell, LockerCell.class);
        Locker locker =
                Hibernate.unproxy(cell.locker, Locker.class);

        return new ParcelView(
                parcel.id,
                parcel.carrierName,
                parcel.trackingNumber,
                locker.lockerNumber,
                locker.location,
                cell.cellNumber,
                parcel.status,
                parcel.storedAt,
                parcel.expiresAt,
                parcel.pickedUpAt
        );
    }
    public record ParcelDetailView(
            ParcelView parcel,
            String pickupCode,
            String qrCodeDataUrl
    ) {}
}
