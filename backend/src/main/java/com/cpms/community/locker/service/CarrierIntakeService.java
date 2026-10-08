package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.hibernate.Hibernate;
@Service
public class CarrierIntakeService {
    public record BeginResult(
            String sessionToken,
            String cellNumber,
            String lockerLocation,
            Instant expiresAt
    ) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LockerRepository lockers;
    private final LockerCellRepository cells;
    private final AccountRepository accounts;
    private final CarrierIntakeSessionRepository sessions;
    private final PickupCodeService pickupCodes;

    private final ParcelRepository parcels;
    private final PickupEmailQueueService emailQueue;
    public CarrierIntakeService(
            LockerRepository lockers,
            LockerCellRepository cells,
            AccountRepository accounts,
            CarrierIntakeSessionRepository sessions,
            PickupCodeService pickupCodes,
            ParcelRepository parcels,
            PickupEmailQueueService emailQueue
    ) {
        this.lockers = lockers;
        this.cells = cells;
        this.accounts = accounts;
        this.sessions = sessions;
        this.pickupCodes = pickupCodes;
        this.parcels = parcels;
        this.emailQueue = emailQueue;
    }

    @Transactional
    public BeginResult begin(
            Long lockerId,
            String room,
            Long residentId,
            String carrierName,
            CellSize packageSize
    ) {
        return begin(lockerId,room,residentId,carrierName,packageSize,null);
    }
    @Transactional
    public BeginResult begin(Long lockerId,String room,Long residentId,String carrierName,CellSize packageSize,Courier courier) {
        if (room == null || room.isBlank()
                || carrierName == null || carrierName.isBlank()
                || packageSize == null || residentId == null) {
            throw AccountService.fail(
                    HttpStatus.BAD_REQUEST, "Missing intake information."
            );
        }

        Locker locker = lockers.lockById(lockerId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Locker not found."
                ));

        if (locker.status != LockerStatus.ACTIVE) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT, "Locker is disabled."
            );
        }

        Account resident = accounts.findById(residentId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Resident not found."
                ));

        if (!resident.community.equals(locker.community)
                || resident.role != Account.Role.RESIDENT
                || !room.strip().equals(resident.room)) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Resident not found in this room."
            );
        }

        if (resident.status != Account.Status.APPROVED) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT, "Resident approval is required."
            );
        }

        List<LockerCell> available = cells.lockAvailableCells(
                lockerId,
                locker.community,
                LockerStatus.ACTIVE,
                packageSize,
                CellStatus.AVAILABLE,
                PageRequest.of(0, 1)
        );

        if (available.isEmpty()) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "No available cell for this package size."
            );
        }

        LockerCell cell = available.get(0);
        cell.status = CellStatus.RESERVED;

        byte[] randomBytes = new byte[24];
        RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        CarrierIntakeSession session = new CarrierIntakeSession();
        session.cell = cell;
        session.resident = resident;
        session.carrierName = carrierName.strip();
        if (courier != null) {
            session.registeredBy = courier.companyId;
            session.courierId = courier.id;
            session.courierName = courier.name;
        }
        session.packageSize = packageSize;
        session.tokenHash = pickupCodes.hash(token);
        session.expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        sessions.saveAndFlush(session);

        return new BeginResult(
                token,
                cell.cellNumber,
                locker.location,
                session.expiresAt
        );
    }
    public record ConfirmResult(
            Long parcelId,
            String cellNumber,
            String lockerLocation,
            String pickupCode
    ) {}

    @Transactional
    public ConfirmResult confirm(Long lockerId, String sessionToken) {
        return confirm(lockerId,sessionToken,null);
    }
    @Transactional
    public ConfirmResult confirm(Long lockerId,String sessionToken,Long courierId) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw AccountService.fail(
                    HttpStatus.BAD_REQUEST, "Session token is required."
            );
        }

        CarrierIntakeSession session = sessions
                .lockByTokenHash(pickupCodes.hash(sessionToken))
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Intake session not found."
                ));

        LockerCell cell = Hibernate.unproxy(
                session.cell, LockerCell.class
        );
        Locker locker = Hibernate.unproxy(
                cell.locker, Locker.class
        );
        Account resident = Hibernate.unproxy(
                session.resident, Account.class
        );

        if (!locker.id.equals(lockerId)) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Intake session not found."
            );
        }
        if (!java.util.Objects.equals(session.courierId,courierId))
            throw AccountService.fail(HttpStatus.FORBIDDEN,"Only the courier who started this delivery can confirm it.");

        Instant now = Instant.now();
        if (session.completedAt != null || session.expiredAt != null
                || !now.isBefore(session.expiresAt)) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT, "Intake session is no longer active."
            );
        }

        if (locker.status != LockerStatus.ACTIVE
                || cell.status != CellStatus.RESERVED
                || resident.status != Account.Status.APPROVED) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT, "Intake can no longer be completed."
            );
        }

        Parcel parcel = new Parcel();
        parcel.community = locker.community;
        parcel.resident = resident;
        parcel.cell = cell;
        parcel.carrierName = session.carrierName;
        parcel.packageSize = session.packageSize;
        parcel.status = ParcelStatus.PENDING_PICKUP;
        parcel.intakeSource = IntakeSource.CARRIER_SELF_SERVICE;
        parcel.registeredBy = session.registeredBy;
        parcel.courierId = session.courierId;
        parcel.courierName = session.courierName;
        parcel.storedAt = now;
        parcel.expiresAt = now.plus(7, ChronoUnit.DAYS);

        cell.status = CellStatus.OCCUPIED;
        session.completedAt = now;
        parcels.saveAndFlush(parcel);

        PickupCodeService.IssuedCode issuedCode =
                pickupCodes.createCode(parcel);

        emailQueue.scheduleNewParcel(
                issuedCode.credential(),
                parcel.storedAt
        );

        String pickupCode = issuedCode.rawCode();

        return new ConfirmResult(
                parcel.id,
                cell.cellNumber,
                locker.location,
                pickupCode
        );
    }
}
