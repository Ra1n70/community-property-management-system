package com.cpms.community.locker;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.repository.LockerCellRepository;
import com.cpms.community.locker.repository.LockerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("demo")
@ConditionalOnProperty(name = "demo.locker-seed", havingValue = "true")
public class LockerDemoData {

    private static final String COMMUNITY = "Demo Community";

    @Bean
    CommandLineRunner seedLockerDemo(
            AccountRepository accounts,
            LockerRepository lockers,
            LockerCellRepository cells,
            PasswordEncoder encoder
    ) {
        return args -> {
            if (accounts.findByEmail("resident.demo@cpms.local").isEmpty()) {
                Account resident = new Account();
                resident.email = "resident.demo@cpms.local";
                resident.passwordHash = encoder.encode("DemoResident123!");
                resident.name = "Demo Resident";
                resident.room = "101";
                resident.community = COMMUNITY;
                resident.role = Account.Role.RESIDENT;
                resident.status = Account.Status.APPROVED;
                accounts.saveAndFlush(resident);
            }

            Locker locker = lockers.findByCommunityOrderByLockerNumber(COMMUNITY)
                    .stream()
                    .filter(item -> item.lockerNumber.equals("L-001"))
                    .findFirst()
                    .orElse(null);

            if (locker == null) {
                locker = new Locker();
                locker.community = COMMUNITY;
                locker.lockerNumber = "L-001";
                locker.location = "Main Lobby";
                locker = lockers.saveAndFlush(locker);
            }

            addCell(cells, locker, "A01", CellSize.SMALL);
            addCell(cells, locker, "A02", CellSize.MEDIUM);
            addCell(cells, locker, "A03", CellSize.LARGE);

            System.out.println("Demo locker ID: " + locker.id);
        };
    }

    private void addCell(
            LockerCellRepository cells,
            Locker locker,
            String number,
            CellSize size
    ) {
        if (cells.existsByLockerIdAndCellNumber(locker.id, number)) return;

        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = number;
        cell.size = size;
        cell.status = CellStatus.AVAILABLE;
        cells.saveAndFlush(cell);
    }
}