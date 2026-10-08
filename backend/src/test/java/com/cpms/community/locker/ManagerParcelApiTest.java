package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.cpms.community.locker.service.PickupCodeService;
import com.cpms.community.locker.service.PickupService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.cpms.community.locker.entity.PickupCredential;
import static org.assertj.core.api.Assertions.assertThat;
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
class ManagerParcelApiTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;
    @Autowired ParcelRepository parcels;
    @Autowired PickupEmailJobRepository emailJobs;
    @Autowired PickupCodeService pickupCodes;
    @Autowired PickupService pickup;
    @Autowired PickupCredentialRepository credentials;
    private Account createAccount(
            Account.Role role,
            String community
    ) {
        Account account = new Account();
        account.email = UUID.randomUUID() + "@test.local";
        account.passwordHash = "test-only";
        account.name = "Test " + role;
        account.room = "101";
        account.community = community;
        account.role = role;
        account.status = Account.Status.APPROVED;
        return accounts.saveAndFlush(account);
    }

    private Locker createLocker(String community) {
        Locker locker = new Locker();
        locker.community = community;
        locker.lockerNumber = UUID.randomUUID().toString();
        locker.location = "Main Lobby";
        return lockers.saveAndFlush(locker);
    }

    private Parcel createParcel(
            Account resident,
            Locker locker,
            ParcelStatus status
    ) {
        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = "A01";
        cell.size = CellSize.SMALL;
        cell.status = (status == ParcelStatus.PENDING_PICKUP
                || status == ParcelStatus.EXPIRED)
                ? CellStatus.OCCUPIED
                : CellStatus.AVAILABLE;
        cell = cells.saveAndFlush(cell);

        Parcel parcel = new Parcel();
        parcel.community = resident.community;
        parcel.resident = resident;
        parcel.cell = cell;
        parcel.carrierName = "UPS";
        parcel.packageSize = CellSize.SMALL;
        parcel.status = status;
        parcel.intakeSource = IntakeSource.PROPERTY_STAFF;
        parcel.expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        if (status == ParcelStatus.PICKED_UP) {
            parcel.pickedUpAt = Instant.now();
        }

        return parcels.saveAndFlush(parcel);
    }

    @Test
    void managerCanFilterOnlyOwnCommunityParcels() throws Exception {
        String ownCommunity = "Community-" + UUID.randomUUID();
        String otherCommunity = "Community-" + UUID.randomUUID();

        Account manager = createAccount(
                Account.Role.MANAGER, ownCommunity
        );
        Account ownResident = createAccount(
                Account.Role.RESIDENT, ownCommunity
        );
        Account otherResident = createAccount(
                Account.Role.RESIDENT, otherCommunity
        );

        Locker firstLocker = createLocker(ownCommunity);
        Locker secondLocker = createLocker(ownCommunity);
        Locker otherLocker = createLocker(otherCommunity);

        Parcel pending = createParcel(
                ownResident, firstLocker, ParcelStatus.PENDING_PICKUP
        );
        createParcel(
                ownResident, secondLocker, ParcelStatus.PICKED_UP
        );
        createParcel(
                otherResident, otherLocker, ParcelStatus.PENDING_PICKUP
        );

        mvc.perform(get("/api/manager/parcels")
                        .with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/manager/parcels")
                        .param("status", "PENDING_PICKUP")
                        .param("lockerId", firstLocker.id.toString())
                        .with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id")
                        .value(pending.id.intValue()));

        mvc.perform(get("/api/manager/parcels")
                        .param("lockerId", otherLocker.id.toString())
                        .with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void residentAndAnonymousCannotUseManagerList() throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account resident = createAccount(
                Account.Role.RESIDENT, community
        );

        mvc.perform(get("/api/manager/parcels")
                        .with(user(resident.email).roles("RESIDENT")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/manager/parcels"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    void managerCanStoreParcel() throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = createAccount(Account.Role.MANAGER, community);
        Account resident = createAccount(Account.Role.RESIDENT, community);
        Locker locker = createLocker(community);

        long emailJobsBefore = emailJobs.count();

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = "A01";
        cell.size = CellSize.SMALL;
        cell.status = CellStatus.AVAILABLE;
        cell = cells.saveAndFlush(cell);

        String body = """
            {
              "residentId": %d,
              "cellId": %d,
              "carrierName": "UPS",
              "packageSize": "SMALL"
            }
            """.formatted(resident.id, cell.id);

        mvc.perform(post("/api/manager/parcels")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parcel.lockerId").value(locker.id.intValue()))
                .andExpect(jsonPath("$.parcel.lockerLocation").value("Main Lobby"))
                .andExpect(jsonPath("$.parcel.status").value("PENDING_PICKUP"))
                .andExpect(jsonPath("$.pickupCode").isString());

        org.junit.jupiter.api.Assertions.assertEquals(
                CellStatus.OCCUPIED,
                cells.findById(cell.id).orElseThrow().status
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                emailJobsBefore,
                emailJobs.count()
        );
    }
    @Test
    void intakeRejectsUnapprovedResidentAndOccupiedCell() throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = createAccount(Account.Role.MANAGER, community);
        Account resident = createAccount(Account.Role.RESIDENT, community);
        Locker locker = createLocker(community);

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = "A01";
        cell.size = CellSize.SMALL;
        cell.status = CellStatus.AVAILABLE;
        cell = cells.saveAndFlush(cell);

        long before = parcels.count();
        String body = """
            {
              "residentId": %d,
              "cellId": %d,
              "carrierName": "UPS",
              "packageSize": "SMALL"
            }
            """.formatted(resident.id, cell.id);

        resident.status = Account.Status.PENDING;
        resident = accounts.saveAndFlush(resident);

        mvc.perform(post("/api/manager/parcels")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        resident.status = Account.Status.APPROVED;
        accounts.saveAndFlush(resident);
        cell.status = CellStatus.OCCUPIED;
        cells.saveAndFlush(cell);

        mvc.perform(post("/api/manager/parcels")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        org.junit.jupiter.api.Assertions.assertEquals(before, parcels.count());
    }
    @Test
    void managerCanRetrieveOnlyOwnExpiredParcel() throws Exception {
        String ownCommunity = "Community-" + UUID.randomUUID();
        String otherCommunity = "Community-" + UUID.randomUUID();

        Account manager = createAccount(
                Account.Role.MANAGER, ownCommunity
        );
        Account ownResident = createAccount(
                Account.Role.RESIDENT, ownCommunity
        );
        Account otherResident = createAccount(
                Account.Role.RESIDENT, otherCommunity
        );

        Parcel expired = createParcel(
                ownResident,
                createLocker(ownCommunity),
                ParcelStatus.EXPIRED
        );
        Parcel pending = createParcel(
                ownResident,
                createLocker(ownCommunity),
                ParcelStatus.PENDING_PICKUP
        );
        Parcel other = createParcel(
                otherResident,
                createLocker(otherCommunity),
                ParcelStatus.EXPIRED
        );

        mvc.perform(post("/api/manager/parcels/" + expired.id + "/retrieve")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETRIEVED"));

        org.junit.jupiter.api.Assertions.assertEquals(
                CellStatus.AVAILABLE,
                cells.findById(expired.cell.id).orElseThrow().status
        );
        org.junit.jupiter.api.Assertions.assertNotNull(
                parcels.findById(expired.id).orElseThrow().retrievedAt
        );

        mvc.perform(post("/api/manager/parcels/" + pending.id + "/retrieve")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf()))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/manager/parcels/" + other.id + "/retrieve")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }
    @Test
    void emailIsDisabledAndRegenerationInvalidatesOldCode()
            throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = createAccount(
                Account.Role.MANAGER, community
        );
        Account resident = createAccount(
                Account.Role.RESIDENT, community
        );
        Locker locker = createLocker(community);
        Parcel parcel = createParcel(
                resident, locker, ParcelStatus.PENDING_PICKUP
        );

        PickupCodeService.IssuedCode original =
                pickupCodes.createCode(parcel);

        mvc.perform(post("/api/manager/parcels/"
                        + parcel.id + "/resend")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf()))
                .andExpect(status().isGone());

        assertThat(pickupCodes.codeForDelivery(
                credentials.findById(original.credential().id)
                        .orElseThrow()
        )).isEqualTo(original.rawCode());

        mvc.perform(post("/api/manager/parcels/"
                        + parcel.id + "/regenerate-code")
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status")
                        .value("PICKUP_CODE_UPDATED"));

        assertThat(credentials.findById(original.credential().id)
                .orElseThrow().status)
                .isEqualTo(PickupCredentialStatus.INVALIDATED);

        PickupCredential replacement = credentials
                .findFirstByParcelIdAndStatusOrderByCreatedAtDesc(
                        parcel.id, PickupCredentialStatus.ACTIVE
                )
                .orElseThrow();
        String newCode = pickupCodes.codeForDelivery(replacement);

        assertThat(newCode).isNotEqualTo(original.rawCode());
        assertThat(pickup.pickup(locker.id, original.rawCode()).result())
                .isEqualTo(PickupService.Result.INVALID_CODE);
        assertThat(pickup.pickup(locker.id, newCode).result())
                .isEqualTo(PickupService.Result.SUCCESS);
    }
    @Test
    void cannotResendOrRegenerateOtherCommunityOrExpiredParcel()
            throws Exception {
        String ownCommunity = "Community-" + UUID.randomUUID();
        String otherCommunity = "Community-" + UUID.randomUUID();

        Account manager = createAccount(
                Account.Role.MANAGER, ownCommunity
        );
        Account ownResident = createAccount(
                Account.Role.RESIDENT, ownCommunity
        );
        Account otherResident = createAccount(
                Account.Role.RESIDENT, otherCommunity
        );

        Parcel expired = createParcel(
                ownResident,
                createLocker(ownCommunity),
                ParcelStatus.EXPIRED
        );
        Parcel other = createParcel(
                otherResident,
                createLocker(otherCommunity),
                ParcelStatus.PENDING_PICKUP
        );

        long jobsBefore = emailJobs.count();

        for (String action : new String[]{"regenerate-code"}) {
            mvc.perform(post("/api/manager/parcels/"
                            + other.id + "/" + action)
                            .with(user(manager.email).roles("MANAGER"))
                            .with(csrf()))
                    .andExpect(status().isNotFound());

            mvc.perform(post("/api/manager/parcels/"
                            + expired.id + "/" + action)
                            .with(user(manager.email).roles("MANAGER"))
                            .with(csrf()))
                    .andExpect(status().isConflict());
        }

        assertThat(emailJobs.count()).isEqualTo(jobsBefore);
    }
    @Test
    void residentSearchOnlyReturnsApprovedResidentsInManagersCommunity()
            throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = createAccount(Account.Role.MANAGER, community);

        Account approved = createAccount(Account.Role.RESIDENT, community);
        approved.name = "Alice";
        approved.room = "B-101";
        accounts.saveAndFlush(approved);

        Account pending = createAccount(Account.Role.RESIDENT, community);
        pending.name = "Pending Resident";
        pending.room = "B-101";
        pending.status = Account.Status.PENDING;
        accounts.saveAndFlush(pending);

        Account otherCommunity = createAccount(
                Account.Role.RESIDENT,
                "Community-" + UUID.randomUUID()
        );
        otherCommunity.room = "B-101";
        accounts.saveAndFlush(otherCommunity);

        mvc.perform(get("/api/manager/parcels/residents")
                        .param("query", "B-101")
                        .with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(approved.id.intValue()))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[0].room").value("B-101"))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void residentSearchRequiresManagerAndNonBlankQuery()
            throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = createAccount(Account.Role.MANAGER, community);
        Account resident = createAccount(Account.Role.RESIDENT, community);

        mvc.perform(get("/api/manager/parcels/residents")
                        .param("query", "   ")
                        .with(user(manager.email).roles("MANAGER")))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/manager/parcels/residents")
                        .param("query", "101")
                        .with(user(resident.email).roles("RESIDENT")))
                .andExpect(status().isForbidden());
    }
}
