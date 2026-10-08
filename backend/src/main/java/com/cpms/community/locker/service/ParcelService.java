package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.LockerCellRepository;
import com.cpms.community.locker.repository.ParcelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cpms.community.locker.entity.Locker;
import org.hibernate.Hibernate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class ParcelService {

    public record IntakeResult(
            Parcel parcel,
            Locker locker,
            String pickupCode
    ) {}

    private final ParcelRepository parcels;
    private final LockerCellRepository cells;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final PickupCodeService pickupCodes;

    private final PickupEmailQueueService emailQueue;
    public ParcelService(
            ParcelRepository parcels,
            LockerCellRepository cells,
            AccountRepository accounts,
            AccountService accountService,
            PickupCodeService pickupCodes,
            PickupEmailQueueService emailQueue
    ) {
        this.parcels = parcels;
        this.cells = cells;
        this.accounts = accounts;
        this.accountService = accountService;
        this.pickupCodes = pickupCodes;
        this.emailQueue = emailQueue;
    }

    private Account requireManager(String email) {
        Account manager = accountService.current(email);

        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN,
                    "Manager access required."
            );
        }

        return manager;
    }

    @Transactional
    public IntakeResult propertyIntake(
            String email,
            Long residentId,
            Long cellId,
            String carrierName,
            String trackingNumber,
            CellSize packageSize
    ) {
        Account manager = requireManager(email);

        Account resident = accounts.lockById(residentId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND,
                        "Resident not found."
                ));

        if (!resident.community.equals(manager.community)
                || resident.role != Account.Role.RESIDENT) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND,
                    "Resident not found."
            );
        }

        if (resident.status != Account.Status.APPROVED) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Resident approval is required."
            );
        }

        LockerCell cell = cells.lockByIdAndCommunity(
                cellId,
                manager.community
        ).orElseThrow(() -> AccountService.fail(
                HttpStatus.NOT_FOUND,
                "Locker cell not found."
        ));
        Locker locker = Hibernate.unproxy(cell.locker, Locker.class);
        if (locker.status != LockerStatus.ACTIVE) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Locker is disabled."
            );
        }

        if (cell.status != CellStatus.AVAILABLE) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Locker cell is not available."
            );
        }

        if (cell.size != packageSize) {
            throw AccountService.fail(
                    HttpStatus.BAD_REQUEST,
                    "Locker cell size does not match package size."
            );
        }

        Instant now = Instant.now();

        Parcel parcel = new Parcel();
        parcel.community = manager.community;
        parcel.resident = resident;
        parcel.cell = cell;
        parcel.carrierName = carrierName.strip();
        parcel.trackingNumber =
                normalizeOptional(trackingNumber);
        parcel.packageSize = packageSize;
        parcel.status = ParcelStatus.PENDING_PICKUP;
        parcel.intakeSource =
                IntakeSource.PROPERTY_STAFF;
        parcel.registeredBy = manager.id;
        parcel.storedAt = now;
        parcel.expiresAt =
                now.plus(7, ChronoUnit.DAYS);

        cell.status = CellStatus.OCCUPIED;

        parcels.saveAndFlush(parcel);

        PickupCodeService.IssuedCode issuedCode =
                pickupCodes.createCode(parcel);
        emailQueue.scheduleNewParcel(
                issuedCode.credential(),
                parcel.storedAt
        );

        return new IntakeResult(
                parcel,
                locker,
                issuedCode.rawCode()
        );
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }
}
