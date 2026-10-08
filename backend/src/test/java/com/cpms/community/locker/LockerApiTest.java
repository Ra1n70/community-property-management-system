package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.repository.LockerCellRepository;
import com.cpms.community.locker.repository.LockerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "locker.pickup-code-secret=test-only-secret",
        "demo.manager-password="
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class LockerApiTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;

    private Account manager(String community) {
        Account account = new Account();
        account.email = UUID.randomUUID() + "@test.local";
        account.passwordHash = "test-only";
        account.name = "Test Manager";
        account.community = community;
        account.role = Account.Role.MANAGER;
        account.status = Account.Status.APPROVED;
        return accounts.saveAndFlush(account);
    }

    private Locker locker(String community) {
        Locker locker = new Locker();
        locker.community = community;
        locker.lockerNumber = UUID.randomUUID().toString();
        locker.location = "Main Lobby";
        return lockers.saveAndFlush(locker);
    }

    private LockerCell cell(Locker locker, String number, CellStatus status) {
        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = number;
        cell.size = CellSize.SMALL;
        cell.status = status;
        return cells.saveAndFlush(cell);
    }

    @Test
    void availableCellCanBeResizedAndDisabled() throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = manager(community);
        Locker locker = locker(community);
        LockerCell cell = cell(locker, "A01", CellStatus.AVAILABLE);

        mvc.perform(patch("/api/manager/lockers/{lockerId}/cells/{cellId}",
                        locker.id, cell.id)
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"size":"MEDIUM","disabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value("MEDIUM"))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void occupiedAndReservedCellsCannotBeEdited() throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = manager(community);
        Locker locker = locker(community);
        LockerCell occupied = cell(locker, "A01", CellStatus.OCCUPIED);
        LockerCell reserved = cell(locker, "A02", CellStatus.RESERVED);

        for (LockerCell cell : new LockerCell[]{occupied, reserved}) {
            mvc.perform(patch("/api/manager/lockers/{lockerId}/cells/{cellId}",
                            locker.id, cell.id)
                            .with(user(manager.email).roles("MANAGER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"disabled":true}
                                    """))
                    .andExpect(status().isConflict());
        }
    }

    @Test
    void managerCannotEditAnotherCommunityCell() throws Exception {
        Account manager = manager("Community-" + UUID.randomUUID());
        Locker otherLocker = locker("Community-" + UUID.randomUUID());
        LockerCell otherCell = cell(otherLocker, "A01", CellStatus.AVAILABLE);

        mvc.perform(patch("/api/manager/lockers/{lockerId}/cells/{cellId}",
                        otherLocker.id, otherCell.id)
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disabled":true}
                                """))
                .andExpect(status().isNotFound());
    }
    @Test
    void emptyLockerCanBeDisabledAndLocationChanged() throws Exception {
        String community = "Community-" + UUID.randomUUID();
        Account manager = manager(community);
        Locker locker = locker(community);
        cell(locker, "A01", CellStatus.AVAILABLE);

        mvc.perform(patch("/api/manager/lockers/{lockerId}", locker.id)
                        .with(user(manager.email).roles("MANAGER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"location":"B1 快递间","status":"DISABLED"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("B1 快递间"))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void lockerWithOccupiedOrReservedCellCannotBeDisabled() throws Exception {
        for (CellStatus cellStatus :
                new CellStatus[]{CellStatus.OCCUPIED, CellStatus.RESERVED}) {
            String community = "Community-" + UUID.randomUUID();
            Account manager = manager(community);
            Locker locker = locker(community);
            cell(locker, "A01", cellStatus);

            mvc.perform(patch("/api/manager/lockers/{lockerId}", locker.id)
                            .with(user(manager.email).roles("MANAGER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {"status":"DISABLED"}
                                """))
                    .andExpect(status().isConflict());
        }
    }

    @Test void deleteUnusedCellAndEnforceAccess() throws Exception {
        Account owner = manager("delete-" + UUID.randomUUID());
        Locker locker = locker(owner.community);
        LockerCell cell = cell(locker, "D1", CellStatus.AVAILABLE);
        String url = "/api/manager/lockers/" + locker.id + "/cells/" + cell.id;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).with(user(owner.email).roles("RESIDENT")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).with(user(owner.email).roles("MANAGER"))).andExpect(status().isForbidden());
        Account other = manager("other-" + UUID.randomUUID());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).with(user(other.email).roles("MANAGER")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(cells.existsById(cell.id)).isFalse();
    }

    @Test void deleteRejectsBusyCellAndMismatchedLocker() throws Exception {
        Account owner = manager("delete-" + UUID.randomUUID());
        Locker locker = locker(owner.community);
        for (CellStatus state : new CellStatus[]{CellStatus.OCCUPIED, CellStatus.RESERVED}) {
            LockerCell cell = cell(locker, state.name(), state);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manager/lockers/{l}/cells/{c}",locker.id,cell.id).with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isConflict());
            org.assertj.core.api.Assertions.assertThat(cells.existsById(cell.id)).isTrue();
        }
        LockerCell empty = cell(locker,"empty",CellStatus.DISABLED);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manager/lockers/{l}/cells/{c}",locker(owner.community).id,empty.id).with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isNotFound());
    }

    @Autowired com.cpms.community.locker.repository.CarrierIntakeSessionRepository intakes;
    @Autowired com.cpms.community.locker.repository.ParcelRepository parcels;
    @Test void deletePreservesHistoricalRecords() throws Exception {
        Account owner = manager("delete-" + UUID.randomUUID());
        Locker locker = locker(owner.community);
        LockerCell reservedBefore = cell(locker,"past-reservation",CellStatus.AVAILABLE);
        var session = new com.cpms.community.locker.entity.CarrierIntakeSession();
        session.cell=reservedBefore; session.resident=owner; session.tokenHash=UUID.randomUUID().toString();
        session.carrierName="Test"; session.packageSize=CellSize.SMALL;
        session.expiresAt=java.time.Instant.now().minusSeconds(60); session.expiredAt=java.time.Instant.now();
        intakes.saveAndFlush(session);
        LockerCell used = cell(locker,"past-parcel",CellStatus.AVAILABLE);
        var parcel = new com.cpms.community.locker.entity.Parcel();
        parcel.cell=used; parcel.resident=owner; parcel.community=owner.community; parcel.carrierName="Test";
        parcel.packageSize=CellSize.SMALL; parcel.intakeSource=com.cpms.community.locker.enums.IntakeSource.values()[0];
        parcel.status=com.cpms.community.locker.enums.ParcelStatus.PICKED_UP; parcel.expiresAt=java.time.Instant.now();
        parcels.saveAndFlush(parcel);
        for (LockerCell cell : new LockerCell[]{reservedBefore,used}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manager/lockers/{l}/cells/{c}",locker.id,cell.id).with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isConflict());
            org.assertj.core.api.Assertions.assertThat(cells.existsById(cell.id)).isTrue();
        }
    }

    @Test
    void deleteLockerRequiresEmptyLockerAndManagerInSameCommunity() throws Exception {
        String community = "Delete-" + UUID.randomUUID();
        Account owner = manager(community);
        Account foreign = manager("Other-" + UUID.randomUUID());
        Locker locker = locker(community);
        String url = "/api/manager/lockers/" + locker.id;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(user(owner.email).roles("RESIDENT")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(user(owner.email).roles("MANAGER"))).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(user(foreign.email).roles("MANAGER")).with(csrf())).andExpect(status().isNotFound());
        LockerCell cell = cell(locker, "A", CellStatus.AVAILABLE);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url + "/cells/" + cell.id)
                .with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(lockers.existsById(locker.id)).isFalse();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(user(owner.email).roles("MANAGER")).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void createByLocationAndRejectDuplicate() throws Exception {
        Account owner = manager("Locations-" + UUID.randomUUID());
        String body = "{\"location\":\"Lobby\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/manager/lockers")
                .with(user(owner.email).roles("MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/manager/lockers")
                .with(user(owner.email).roles("MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/locker-panel/locations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.location == 'Lobby')]").isNotEmpty());
    }
}