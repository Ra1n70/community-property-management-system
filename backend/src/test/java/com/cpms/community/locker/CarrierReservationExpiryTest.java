package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import com.cpms.community.locker.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "locker.pickup-code-secret=test-only-secret",
        "demo.manager-password="
})
@ActiveProfiles("demo")
class CarrierReservationExpiryTest {
    @Autowired AccountRepository accounts;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;
    @Autowired CarrierIntakeSessionRepository sessions;
    @Autowired CarrierIntakeService carrier;
    @Autowired CarrierReservationExpiryService expiry;
    @Autowired PickupCodeService pickupCodes;

    @Test
    void expiredReservationReleasesCellAndCannotBeConfirmed() {
        String unique = UUID.randomUUID().toString();

        Account resident = new Account();
        resident.email = unique + "@test.local";
        resident.passwordHash = "test-only";
        resident.name = "Test Resident";
        resident.room = "101";
        resident.community = unique;
        resident.role = Account.Role.RESIDENT;
        resident.status = Account.Status.APPROVED;
        resident = accounts.saveAndFlush(resident);

        Locker locker = new Locker();
        locker.community = unique;
        locker.lockerNumber = unique;
        locker.location = "Main Lobby";
        locker = lockers.saveAndFlush(locker);

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = "A01";
        cell.size = CellSize.SMALL;
        cell.status = CellStatus.AVAILABLE;
        cell = cells.saveAndFlush(cell);

        CarrierIntakeService.BeginResult result = carrier.begin(
                locker.id, "101", resident.id, "UPS", CellSize.SMALL
        );

        assertThat(cells.findById(cell.id).orElseThrow().status)
                .isEqualTo(CellStatus.RESERVED);

        String tokenHash = pickupCodes.hash(result.sessionToken());
        CarrierIntakeSession session = sessions.findAll().stream()
                .filter(s -> s.tokenHash.equals(tokenHash))
                .findFirst()
                .orElseThrow();

        assertThat(expiry.expireOne(
                session.id, result.expiresAt().plusSeconds(1)
        )).isTrue();

        assertThat(cells.findById(cell.id).orElseThrow().status)
                .isEqualTo(CellStatus.AVAILABLE);
        assertThat(sessions.findById(session.id).orElseThrow().expiredAt)
                .isNotNull();

        Long lockerId = locker.id;
        assertThatThrownBy(() ->
                carrier.confirm(lockerId, result.sessionToken())
        ).hasMessageContaining("Intake session is no longer active");
    }
}
