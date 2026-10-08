package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.enums.LockerStatus;
import com.cpms.community.locker.repository.LockerCellRepository;
import com.cpms.community.locker.repository.LockerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hibernate.Hibernate;
import com.cpms.community.locker.repository.ParcelRepository;
import com.cpms.community.locker.repository.CarrierIntakeSessionRepository;
import java.util.List;

@Service
public class LockerService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.cpms.community.AccountRepository accounts;
    private final LockerRepository lockers;
    private final LockerCellRepository cells;
    private final AccountService accountService;
    private final ParcelRepository parcels;
    private final CarrierIntakeSessionRepository intakes;

    public LockerService(
            LockerRepository lockers,
            LockerCellRepository cells,
            AccountService accountService, ParcelRepository parcels, CarrierIntakeSessionRepository intakes
    ) {
        this.lockers = lockers;
        this.cells = cells;
        this.accountService = accountService;
        this.parcels = parcels;
        this.intakes = intakes;
    }

    private Account requireManager(String email) {
        Account manager = accountService.current(email);

        if (manager.role != Account.Role.MANAGER) {
            throw AccountService.fail(
                    HttpStatus.FORBIDDEN,
                    "Manager access required."
            );
        }

        return manager;
    }

    @Transactional(readOnly = true)
    public List<Locker> listLockers(String email) {
        Account manager = requireManager(email);

        return lockers.findByCommunityOrderByLockerNumber(
                manager.community
        );
    }

    @Transactional
    public Locker createLocker(
            String email,
            String lockerNumber,
            String location
    ) {
        Account manager = requireManager(email);

        accounts.lockCommunityManagers(manager.community);
        String normalizedNumber = java.util.UUID.randomUUID().toString();
        String normalizedLocation = location.strip();

        if (lockers.existsByCommunityAndLockerNumber(
                manager.community,
                normalizedNumber
        )) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Locker number already exists."
            );
        }

        if (lockers.findByCommunityOrderByLockerNumber(manager.community).stream().anyMatch(l -> l.location.equalsIgnoreCase(normalizedLocation))) {
            throw AccountService.fail(HttpStatus.CONFLICT, "A locker already exists at this location.");
        }
        Locker locker = new Locker();
        locker.community = manager.community;
        locker.lockerNumber = normalizedNumber;
        locker.location = normalizedLocation;
        locker.status = LockerStatus.ACTIVE;

        return lockers.saveAndFlush(locker);
    }

    @Transactional(readOnly = true)
    public List<LockerCell> listCells(
            String email,
            Long lockerId
    ) {
        Account manager = requireManager(email);

        Locker locker = lockers.findByIdAndCommunity(
                lockerId,
                manager.community
        ).orElseThrow(() -> AccountService.fail(
                HttpStatus.NOT_FOUND,
                "Locker not found."
        ));

        return cells.findByLockerIdOrderByCellNumber(locker.id);
    }

    @Transactional
    public LockerCell createCell(
            String email,
            Long lockerId,
            String cellNumber,
            CellSize size
    ) {
        Account manager = requireManager(email);

        Locker locker = lockers.lockById(lockerId).orElseThrow(() -> AccountService.fail(
                HttpStatus.NOT_FOUND, "Locker not found."));
        if (!locker.community.equals(manager.community)) {
            throw AccountService.fail(HttpStatus.NOT_FOUND, "Locker not found.");
        }

        String normalizedCellNumber = cellNumber.strip();

        if (cells.existsByLockerIdAndCellNumber(
                locker.id,
                normalizedCellNumber
        )) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Cell number already exists in this locker."
            );
        }

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = normalizedCellNumber;
        cell.size = size;
        cell.status = CellStatus.AVAILABLE;

        return cells.saveAndFlush(cell);
    }
    @Transactional
    public LockerCell updateCell(
            String email,
            Long lockerId,
            Long cellId,
            CellSize newSize,
            Boolean disabled
    ) {
        Account manager = requireManager(email);

        if (newSize == null && disabled == null) {
            throw AccountService.fail(
                    HttpStatus.BAD_REQUEST,
                    "Choose a size or disabled status to update."
            );
        }

        LockerCell cell = Hibernate.unproxy(
                cells.lockByIdAndCommunity(
                        cellId, manager.community
                ).orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Locker cell not found."
                )),
                LockerCell.class
        );

        Locker locker = Hibernate.unproxy(
                cell.locker, Locker.class
        );
        if (!locker.id.equals(lockerId)) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Locker cell not found."
            );
        }

        if (cell.status == CellStatus.OCCUPIED
                || cell.status == CellStatus.RESERVED) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Occupied or reserved cells cannot be edited."
            );
        }

        if (newSize != null) {
            cell.size = newSize;
        }
        if (disabled != null) {
            cell.status = disabled
                    ? CellStatus.DISABLED
                    : CellStatus.AVAILABLE;
        }

        return cell;
    }
    @Transactional
    public Locker updateLocker(
            String email,
            Long lockerId,
            String newLocation,
            LockerStatus newStatus
    ) {
        Account manager = requireManager(email);

        accounts.lockCommunityManagers(manager.community);
        if (newLocation == null && newStatus == null) {
            throw AccountService.fail(
                    HttpStatus.BAD_REQUEST,
                    "Choose a location or status to update."
            );
        }

        Locker locker = lockers.lockById(lockerId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Locker not found."
                ));

        if (!locker.community.equals(manager.community)) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Locker not found."
            );
        }

        if (newLocation != null) {
            String location = newLocation.strip();
            if (location.isEmpty() || location.length() > 200) {
                throw AccountService.fail(
                        HttpStatus.BAD_REQUEST,
                        "Location must be 1 to 200 characters."
                );
            }
            if (lockers.findByCommunityOrderByLockerNumber(manager.community).stream().anyMatch(l -> !l.id.equals(lockerId) && l.location.equalsIgnoreCase(location))) {
                throw AccountService.fail(HttpStatus.CONFLICT, "A locker already exists at this location.");
            }
            locker.location = location;
        }

        if (newStatus == LockerStatus.DISABLED
                && cells.existsByLockerIdAndStatusIn(
                lockerId,
                List.of(CellStatus.OCCUPIED, CellStatus.RESERVED)
        )) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "Empty all occupied or reserved cells before disabling this locker."
            );
        }

        if (newStatus != null) {
            locker.status = newStatus;
        }

        return locker;
    }
    @Transactional(readOnly = true)
    public List<LockerCell> availableCells(
            String email,
            Long lockerId,
            CellSize size
    ) {
        Account manager = requireManager(email);

        Locker locker = lockers.findByIdAndCommunity(
                lockerId, manager.community
        ).orElseThrow(() -> AccountService.fail(
                HttpStatus.NOT_FOUND, "Locker not found."
        ));

        if (locker.status != LockerStatus.ACTIVE) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT, "Locker is disabled."
            );
        }

        return cells.findByLockerIdOrderByCellNumber(lockerId)
                .stream()
                .filter(cell -> cell.status == CellStatus.AVAILABLE)
                .filter(cell -> cell.size == size)
                .toList();
    }
    @Transactional
    public void deleteLocker(String email, Long lockerId) {
        Account manager = requireManager(email);
        Locker locker = lockers.lockById(lockerId)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Locker not found."));
        if (!locker.community.equals(manager.community)) {
            throw AccountService.fail(HttpStatus.NOT_FOUND, "Locker not found.");
        }
        if (!cells.findByLockerIdOrderByCellNumber(lockerId).isEmpty()) {
            throw AccountService.fail(HttpStatus.CONFLICT,
                    "Delete all unused cells first. If cells have parcel or intake history, disable this locker instead.");
        }
        lockers.delete(locker);
        lockers.flush();
    }
    @Transactional
    public void deleteCell(String email, Long lockerId, Long cellId) {
        Account manager = requireManager(email);
        LockerCell cell = Hibernate.unproxy(cells.lockByIdAndCommunity(cellId, manager.community)
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Locker cell not found.")), LockerCell.class);
        if (!Hibernate.unproxy(cell.locker, Locker.class).id.equals(lockerId)) {
            throw AccountService.fail(HttpStatus.NOT_FOUND, "Locker cell not found.");
        }
        if (cell.status != CellStatus.AVAILABLE && cell.status != CellStatus.DISABLED) {
            throw AccountService.fail(HttpStatus.CONFLICT, "Occupied or reserved cells cannot be deleted.");
        }
        if (parcels.existsByCellId(cellId) || intakes.existsByCellId(cellId)) {
            throw AccountService.fail(HttpStatus.CONFLICT,
                    "This cell has parcel or intake history. Disable it instead to preserve records.");
        }
        cells.delete(cell);
        cells.flush();
    }
}
