package com.cpms.community.locker.service;

import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.hibernate.Hibernate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class PickupService {

    public enum Result {
        SUCCESS, INVALID_CODE, ALREADY_USED, EXPIRED,
        LOCKED, LOCKER_DISABLED
    }

    public record PickupResult(
            Result result,
            String cellNumber,
            Instant lockedUntil
    ) {}

    private final LockerRepository lockers;
    private final LockerPickupAttemptRepository attempts;
    private final PickupCredentialRepository credentials;
    private final PickupCodeService pickupCodes;

    public PickupService(
            LockerRepository lockers,
            LockerPickupAttemptRepository attempts,
            PickupCredentialRepository credentials,
            PickupCodeService pickupCodes
    ) {
        this.lockers = lockers;
        this.attempts = attempts;
        this.credentials = credentials;
        this.pickupCodes = pickupCodes;
    }

    @Transactional
    public PickupResult pickup(Long lockerId, String rawCode) {
        Locker locker = lockers.lockById(lockerId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Locker not found."
                ));

        if (locker.status != LockerStatus.ACTIVE) {
            return new PickupResult(Result.LOCKER_DISABLED, null, null);
        }

        LockerPickupAttempt attempt = attempts.lockByLockerId(lockerId)
                .orElseGet(() -> {
                    LockerPickupAttempt created = new LockerPickupAttempt();
                    created.locker = locker;
                    return attempts.saveAndFlush(created);
                });

        Instant now = Instant.now();

        if (attempt.lockedUntil != null) {
            if (now.isBefore(attempt.lockedUntil)) {
                return new PickupResult(
                        Result.LOCKED, null, attempt.lockedUntil
                );
            }
            attempt.lockedUntil = null;
            attempt.consecutiveFailures = 0;
        }

        if (rawCode == null || !rawCode.matches("\\d{6}")) {
            return failed(attempt, Result.INVALID_CODE, now);
        }

        PickupCredential credential = credentials
                .lockByCodeHash(pickupCodes.hash(rawCode))
                .orElse(null);

        if (credential == null) {
            return failed(attempt, Result.INVALID_CODE, now);
        }

        Parcel parcel = Hibernate.unproxy(credential.parcel, Parcel.class);
        LockerCell cell = Hibernate.unproxy(parcel.cell, LockerCell.class);
        Locker parcelLocker = Hibernate.unproxy(cell.locker, Locker.class);

        // 码只能在存放该包裹的柜机上使用。
        if (!parcelLocker.id.equals(lockerId)) {
            return failed(attempt, Result.INVALID_CODE, now);
        }

        if (credential.status == PickupCredentialStatus.USED
                || parcel.status == ParcelStatus.PICKED_UP) {
            return failed(attempt, Result.ALREADY_USED, now);
        }

        if (credential.status == PickupCredentialStatus.INVALIDATED
                || parcel.status == ParcelStatus.RETRIEVED) {
            return failed(attempt, Result.INVALID_CODE, now);
        }

        if (credential.status == PickupCredentialStatus.EXPIRED
                || !now.isBefore(credential.expiresAt)
                || parcel.status == ParcelStatus.EXPIRED) {
            credential.status = PickupCredentialStatus.EXPIRED;
            if (parcel.status == ParcelStatus.PENDING_PICKUP) {
                parcel.status = ParcelStatus.EXPIRED;
            }
            return failed(attempt, Result.EXPIRED, now);
        }

        if (parcel.status != ParcelStatus.PENDING_PICKUP
                || cell.status != CellStatus.OCCUPIED) {
            return failed(attempt, Result.INVALID_CODE, now);
        }

        credential.status = PickupCredentialStatus.USED;
        credential.usedAt = now;
        parcel.status = ParcelStatus.PICKED_UP;
        parcel.pickedUpAt = now;
        cell.status = CellStatus.AVAILABLE;

        attempt.consecutiveFailures = 0;
        attempt.lockedUntil = null;

        return new PickupResult(
                Result.SUCCESS,
                cell.cellNumber,
                null
        );
    }

    private PickupResult failed(
            LockerPickupAttempt attempt,
            Result reason,
            Instant now
    ) {
        attempt.consecutiveFailures++;

        if (attempt.consecutiveFailures >= 5) {
            attempt.lockedUntil = now.plus(10, ChronoUnit.MINUTES);
            return new PickupResult(
                    Result.LOCKED, null, attempt.lockedUntil
            );
        }

        return new PickupResult(reason, null, null);
    }
}
