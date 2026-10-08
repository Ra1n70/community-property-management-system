package com.cpms.community.locker.service;

import com.cpms.community.locker.repository.PickupEmailJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PickupEmailJobRunner {
    private static final Logger log =
            LoggerFactory.getLogger(PickupEmailJobRunner.class);

    @org.springframework.beans.factory.annotation.Value("${locker.email-enabled:false}")
    private boolean enabled;
    private final PickupEmailJobRepository jobs;
    private final PickupEmailJobProcessor processor;

    public PickupEmailJobRunner(
            PickupEmailJobRepository jobs,
            PickupEmailJobProcessor processor
    ) {
        this.jobs = jobs;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${locker.email-poll-ms:10000}")
    public void sendReadyEmails() {
        if (!enabled) return;
        for (Long id : jobs.findReadyIds(
                Instant.now(), PageRequest.of(0, 100)
        )) {
            try {
                processor.process(id);
            } catch (RuntimeException exception) {
                log.error("Could not process pickup email job {}", id,
                        exception);
            }
        }
    }
}
