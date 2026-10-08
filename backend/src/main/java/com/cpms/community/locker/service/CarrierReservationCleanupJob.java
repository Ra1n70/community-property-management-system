package com.cpms.community.locker.service;

import com.cpms.community.locker.repository.CarrierIntakeSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CarrierReservationCleanupJob {
    private static final Logger log =
            LoggerFactory.getLogger(CarrierReservationCleanupJob.class);

    private final CarrierIntakeSessionRepository sessions;
    private final CarrierReservationExpiryService expiryService;

    public CarrierReservationCleanupJob(
            CarrierIntakeSessionRepository sessions,
            CarrierReservationExpiryService expiryService
    ) {
        this.sessions = sessions;
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString =
            "${locker.reservation-cleanup-delay-ms:60000}")
    public void releaseExpired() {
        Instant now = Instant.now();

        for (Long id : sessions.findExpiredIds(now)) {
            try {
                expiryService.expireOne(id, now);
            } catch (RuntimeException exception) {
                log.error("Could not release carrier reservation {}", id,
                        exception);
            }
        }
    }
}
