package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.cpms.community.locker.service.PickupCodeService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "locker.pickup-code-secret=test-only-secret",
        "demo.manager-password="
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ResidentParcelApiTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;
    @Autowired ParcelRepository parcels;
    @Autowired PickupCodeService pickupCodes;

    private Account createResident(Account.Status status) {
        Account resident = new Account();
        resident.email = UUID.randomUUID() + "@test.local";
        resident.passwordHash = "test-only";
        resident.name = "Test Resident";
        resident.room = "101";
        resident.community = "Demo Community";
        resident.role = Account.Role.RESIDENT;
        resident.status = status;
        return accounts.saveAndFlush(resident);
    }

    private Parcel createParcel(Account resident) {
        Locker locker = new Locker();
        locker.community = resident.community;
        locker.lockerNumber = UUID.randomUUID().toString();
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
        parcel.expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        return parcels.saveAndFlush(parcel);
    }

    @Test
    void approvedResidentOnlySeesOwnParcels() throws Exception {
        Account alice = createResident(Account.Status.APPROVED);
        Account bob = createResident(Account.Status.APPROVED);
        Parcel aliceParcel = createParcel(alice);
        Parcel bobParcel = createParcel(bob);
        String aliceCode = pickupCodes.createCode(aliceParcel).rawCode();

        mvc.perform(get("/api/resident/parcels")
                        .with(user(alice.email).roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id")
                        .value(aliceParcel.id.intValue()))
                .andExpect(jsonPath("$[0].lockerLocation")
                        .value("Main Lobby"))
                .andExpect(jsonPath("$[0].pickupCode").doesNotExist());

        mvc.perform(get("/api/resident/parcels/" + aliceParcel.id)
                        .with(user(alice.email).roles("RESIDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcel.id")
                        .value(aliceParcel.id.intValue()))
                .andExpect(jsonPath("$.pickupCode").value(aliceCode));

        mvc.perform(get("/api/resident/parcels/" + bobParcel.id)
                        .with(user(alice.email).roles("RESIDENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pendingResidentCannotViewParcels() throws Exception {
        Account pending = createResident(Account.Status.PENDING);

        mvc.perform(get("/api/resident/parcels")
                        .with(user(pending.email).roles("RESIDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousVisitorCannotViewParcels() throws Exception {
        mvc.perform(get("/api/resident/parcels"))
                .andExpect(status().isUnauthorized());
    }
}
