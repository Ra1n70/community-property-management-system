package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.PickupEmailJobRepository;
import com.cpms.community.locker.service.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PickupEmailJobProcessorTest {

    @Test
    void sendsReadyEmailAndMarksJobSent() {
        PickupEmailJobRepository jobs =
                mock(PickupEmailJobRepository.class);
        PickupCodeService codes = mock(PickupCodeService.class);
        PickupEmailService emails = mock(PickupEmailService.class);

        Account resident = new Account();
        resident.email = "resident@test.local";

        Locker locker = new Locker();
        locker.location = "Main Lobby";

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = "A01";

        Parcel parcel = new Parcel();
        parcel.resident = resident;
        parcel.cell = cell;
        parcel.status = ParcelStatus.PENDING_PICKUP;

        PickupCredential credential = new PickupCredential();
        credential.parcel = parcel;
        credential.codeNonce = "test-nonce";
        credential.status = PickupCredentialStatus.ACTIVE;
        credential.expiresAt =
                Instant.now().plus(7, ChronoUnit.DAYS);

        PickupEmailJob job = new PickupEmailJob();
        job.credential = credential;
        job.type = PickupEmailType.INITIAL;
        job.dueAt = Instant.now().minusSeconds(1);
        job.nextAttemptAt = job.dueAt;

        when(jobs.lockById(1L)).thenReturn(Optional.of(job));
        when(codes.codeForDelivery(credential))
                .thenReturn("123456");

        new PickupEmailJobProcessor(jobs, codes, emails)
                .process(1L);

        verify(emails).sendPickupNotice(
                resident.email,
                "123456",
                locker.location,
                cell.cellNumber,
                credential.expiresAt
        );
        assertThat(job.sentAt).isNotNull();
    }

    @Test
    void disabledMailRunnerDoesNotProcessOldJobs() {
        PickupEmailJobRepository jobs = mock(PickupEmailJobRepository.class);
        PickupEmailJobProcessor processor = mock(PickupEmailJobProcessor.class);
        new PickupEmailJobRunner(jobs, processor).sendReadyEmails();
        verifyNoInteractions(jobs, processor);
    }
}
