package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.enums.PickupCredentialStatus;
import com.cpms.community.locker.repository.PickupEmailJobRepository;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class PickupEmailJobProcessor {
    private static final Logger log =
            LoggerFactory.getLogger(PickupEmailJobProcessor.class);

    private final PickupEmailJobRepository jobs;
    private final PickupCodeService pickupCodes;
    private final PickupEmailService emailService;

    public PickupEmailJobProcessor(
            PickupEmailJobRepository jobs,
            PickupCodeService pickupCodes,
            PickupEmailService emailService
    ) {
        this.jobs = jobs;
        this.pickupCodes = pickupCodes;
        this.emailService = emailService;
    }

    @Transactional
    public void process(Long jobId) {
        PickupEmailJob job = jobs.lockById(jobId).orElse(null);
        if (job == null || job.sentAt != null
                || job.cancelledAt != null) {
            return;
        }

        Instant now = Instant.now();
        if (now.isBefore(job.dueAt)
                || now.isBefore(job.nextAttemptAt)) {
            return;
        }

        PickupCredential credential = Hibernate.unproxy(
                job.credential, PickupCredential.class
        );
        Parcel parcel = Hibernate.unproxy(
                credential.parcel, Parcel.class
        );

        if (credential.status != PickupCredentialStatus.ACTIVE
                || credential.codeNonce == null
                || !now.isBefore(credential.expiresAt)
                || parcel.status != ParcelStatus.PENDING_PICKUP) {
            job.cancelledAt = now;
            return;
        }

        Account resident = Hibernate.unproxy(
                parcel.resident, Account.class
        );
        LockerCell cell = Hibernate.unproxy(
                parcel.cell, LockerCell.class
        );
        Locker locker = Hibernate.unproxy(
                cell.locker, Locker.class
        );

        try {
            String code = pickupCodes.codeForDelivery(credential);
            emailService.sendPickupNotice(
                    resident.email,
                    code,
                    locker.location,
                    cell.cellNumber,
                    credential.expiresAt
            );
            job.sentAt = Instant.now();
        } catch (RuntimeException exception) {
            job.attempts++;
            job.lastAttemptAt = now;
            job.nextAttemptAt = now.plus(5, ChronoUnit.MINUTES);
            log.warn("Pickup email job {} will be retried", jobId);
        }
    }
}
