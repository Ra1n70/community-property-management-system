package com.cpms.community.locker.service;

import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.repository.ParcelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ParcelExpiryJob {
    private static final Logger log =
            LoggerFactory.getLogger(ParcelExpiryJob.class);

    private final ParcelRepository parcels;
    private final ParcelExpiryService expiryService;

    public ParcelExpiryJob(
            ParcelRepository parcels,
            ParcelExpiryService expiryService
    ) {
        this.parcels = parcels;
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString =
            "${locker.parcel-expiry-delay-ms:60000}")
    public void expireDueParcels() {
        Instant now = Instant.now();

        for (Parcel parcel : parcels.findByStatusAndExpiresAtBefore(
                ParcelStatus.PENDING_PICKUP, now
        )) {
            try {
                expiryService.expireOne(parcel.id, now);
            } catch (RuntimeException exception) {
                log.error("Could not expire parcel {}", parcel.id,
                        exception);
            }
        }
    }
}
