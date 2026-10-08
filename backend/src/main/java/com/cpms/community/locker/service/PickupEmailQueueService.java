package com.cpms.community.locker.service;

import com.cpms.community.locker.entity.PickupCredential;
import com.cpms.community.locker.entity.PickupEmailJob;
import com.cpms.community.locker.enums.PickupEmailType;
import com.cpms.community.locker.repository.PickupEmailJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class PickupEmailQueueService {
    @org.springframework.beans.factory.annotation.Value("${locker.email-enabled:false}")
    private boolean enabled;
    private final PickupEmailJobRepository jobs;

    public PickupEmailQueueService(PickupEmailJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public void scheduleNewParcel(
            PickupCredential credential,
            Instant storedAt
    ) {
        add(credential, PickupEmailType.INITIAL, storedAt);
        add(credential, PickupEmailType.REMINDER_DAY_3,
                storedAt.plus(3, ChronoUnit.DAYS));
        add(credential, PickupEmailType.REMINDER_DAY_6,
                storedAt.plus(6, ChronoUnit.DAYS));
    }
    @Transactional
    public void scheduleResend(PickupCredential credential) {
        add(credential, PickupEmailType.RESEND, Instant.now());
    }

    @Transactional
    public void scheduleRegenerated(
            PickupCredential credential,
            Instant storedAt
    ) {
        Instant now = Instant.now();
        add(credential, PickupEmailType.CODE_REGENERATED, now);

        Instant day3 = storedAt.plus(3, ChronoUnit.DAYS);
        Instant day6 = storedAt.plus(6, ChronoUnit.DAYS);

        if (day3.isAfter(now)) {
            add(credential, PickupEmailType.REMINDER_DAY_3, day3);
        }
        if (day6.isAfter(now)) {
            add(credential, PickupEmailType.REMINDER_DAY_6, day6);
        }
    }

    private void add(
            PickupCredential credential,
            PickupEmailType type,
            Instant dueAt
    ) {
        if (!enabled) return;
        PickupEmailJob job = new PickupEmailJob();
        job.credential = credential;
        job.type = type;
        job.dueAt = dueAt;
        job.nextAttemptAt = dueAt;
        jobs.save(job);
    }
}
