package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import com.cpms.community.locker.service.PickupCodeService;
import com.cpms.community.locker.service.PickupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.cpms.community.locker.service.ParcelExpiryService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "locker.pickup-code-secret=test-only-secret",
        "demo.manager-password="
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class PickupServiceTest {

    @Autowired AccountRepository accounts;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;
    @Autowired ParcelRepository parcels;
    @Autowired PickupCredentialRepository credentials;
    @Autowired LockerPickupAttemptRepository attempts;
    @Autowired PickupCodeService pickupCodes;
    @Autowired PickupService pickup;
    @Autowired MockMvc mvc;
    @Autowired ParcelExpiryService expiryService;

    record Fixture(Long lockerId, Long cellId, Long parcelId,
                   Long credentialId, String code) {}

    private Fixture createParcel(Instant expiresAt) {
        String unique = UUID.randomUUID().toString();

        Account resident = new Account();
        resident.email = unique + "@test.local";
        resident.passwordHash = "test-only";
        resident.name = "Test Resident";
        resident.room = "101";
        resident.community = "Demo Community";
        resident.role = Account.Role.RESIDENT;
        resident.status = Account.Status.APPROVED;
        resident = accounts.saveAndFlush(resident);

        Locker locker = new Locker();
        locker.community = resident.community;
        locker.lockerNumber = unique;
        locker.location = "Main Lobby";
        locker = lockers.saveAndFlush(locker);

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = "A01";
        cell.size = CellSize.SMALL;
        cell.status = CellStatus.OCCUPIED;
        cell = cells.saveAndFlush(cell);

        Parcel parcel = new Parcel();
        parcel.community = resident.community;
        parcel.resident = resident;
        parcel.cell = cell;
        parcel.carrierName = "UPS";
        parcel.packageSize = CellSize.SMALL;
        parcel.status = ParcelStatus.PENDING_PICKUP;
        parcel.intakeSource = IntakeSource.PROPERTY_STAFF;
        parcel.expiresAt = expiresAt;
        parcel = parcels.saveAndFlush(parcel);

        PickupCodeService.IssuedCode issued =
                pickupCodes.createCode(parcel);

        return new Fixture(
                locker.id, cell.id, parcel.id,
                issued.credential().id, issued.rawCode()
        );
    }

    @Test
    void correctCodePicksUpOnceAndReleasesCell() {
        Fixture f = createParcel(
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        assertThat(pickup.pickup(f.lockerId(), f.code()).result())
                .isEqualTo(PickupService.Result.SUCCESS);

        assertThat(parcels.findById(f.parcelId()).orElseThrow().status)
                .isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(cells.findById(f.cellId()).orElseThrow().status)
                .isEqualTo(CellStatus.AVAILABLE);
        assertThat(credentials.findById(f.credentialId())
                .orElseThrow().status)
                .isEqualTo(PickupCredentialStatus.USED);

        assertThat(pickup.pickup(f.lockerId(), f.code()).result())
                .isEqualTo(PickupService.Result.ALREADY_USED);
    }

    @Test
    void fiveWrongCodesLockTheLockerForTenMinutes() {
        Fixture f = createParcel(
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        for (int i = 0; i < 4; i++) {
            assertThat(pickup.pickup(f.lockerId(), "not-a-code").result())
                    .isEqualTo(PickupService.Result.INVALID_CODE);
        }

        PickupService.PickupResult fifth =
                pickup.pickup(f.lockerId(), "not-a-code");

        assertThat(fifth.result())
                .isEqualTo(PickupService.Result.LOCKED);
        assertThat(fifth.lockedUntil()).isAfter(Instant.now());

        assertThat(pickup.pickup(f.lockerId(), f.code()).result())
                .isEqualTo(PickupService.Result.LOCKED);
        assertThat(parcels.findById(f.parcelId()).orElseThrow().status)
                .isEqualTo(ParcelStatus.PENDING_PICKUP);
    }

    @Test
    void expiredCodeDoesNotReleaseCell() {
        Fixture f = createParcel(
                Instant.now().minus(1, ChronoUnit.MINUTES)
        );

        assertThat(pickup.pickup(f.lockerId(), f.code()).result())
                .isEqualTo(PickupService.Result.EXPIRED);
        assertThat(parcels.findById(f.parcelId()).orElseThrow().status)
                .isEqualTo(ParcelStatus.EXPIRED);
        assertThat(cells.findById(f.cellId()).orElseThrow().status)
                .isEqualTo(CellStatus.OCCUPIED);
    }

    @Test
    void anonymousPickupRequiresCsrfButNotLogin() throws Exception {
        Fixture f = createParcel(
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        String url = "/api/locker-panel/" + f.lockerId() + "/pickup";
        String body = "{\"pickupCode\":\"" + f.code() + "\"}";

        mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post(url)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.cellNumber").value("A01"));
    }
    @Test
    void regeneratedCodeCanBeRecreatedAndOldCodeStopsWorking() {
        Fixture f = createParcel(
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        PickupCredential original = credentials
                .findById(f.credentialId())
                .orElseThrow();

        assertThat(original.codeNonce).isNotBlank();
        assertThat(pickupCodes.codeForDelivery(original))
                .isEqualTo(f.code());

        Parcel parcel = parcels.findById(f.parcelId()).orElseThrow();
        PickupCodeService.IssuedCode replacement =
                pickupCodes.regenerateCode(parcel);

        assertThat(replacement.rawCode()).matches("\\d{6}");
        assertThat(replacement.rawCode()).isNotEqualTo(f.code());

        PickupCredential savedReplacement = credentials
                .findById(replacement.credential().id)
                .orElseThrow();

        assertThat(pickupCodes.codeForDelivery(savedReplacement))
                .isEqualTo(replacement.rawCode());

        assertThat(pickup.pickup(f.lockerId(), f.code()).result())
                .isEqualTo(PickupService.Result.INVALID_CODE);

        assertThat(pickup.pickup(
                f.lockerId(), replacement.rawCode()
        ).result()).isEqualTo(PickupService.Result.SUCCESS);
    }
    @Test
    void scheduledExpiryInvalidatesCodeButKeepsCellOccupied() {
        Fixture f = createParcel(
                Instant.now().minus(1, ChronoUnit.MINUTES)
        );

        assertThat(expiryService.expireOne(
                f.parcelId(), Instant.now()
        )).isTrue();

        assertThat(parcels.findById(f.parcelId()).orElseThrow().status)
                .isEqualTo(ParcelStatus.EXPIRED);
        assertThat(credentials.findById(f.credentialId())
                .orElseThrow().status)
                .isEqualTo(PickupCredentialStatus.EXPIRED);
        assertThat(cells.findById(f.cellId()).orElseThrow().status)
                .isEqualTo(CellStatus.OCCUPIED);

        assertThat(pickup.pickup(f.lockerId(), f.code()).result())
                .isEqualTo(PickupService.Result.EXPIRED);
    }
}
