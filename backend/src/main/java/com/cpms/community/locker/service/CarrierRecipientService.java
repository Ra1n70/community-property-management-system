package com.cpms.community.locker.service;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.enums.LockerStatus;
import com.cpms.community.locker.repository.LockerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CarrierRecipientService {
    public record Recipient(Long residentId, String maskedName) {}

    private final LockerRepository lockers;
    private final AccountRepository accounts;

    public CarrierRecipientService(
            LockerRepository lockers,
            AccountRepository accounts
    ) {
        this.lockers = lockers;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<Recipient> lookup(Long lockerId, String room) {
        if (room == null || room.isBlank()) {
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Room is required.");
        }

        Locker locker = lockers.findById(lockerId)
                .orElseThrow(() -> AccountService.fail(
                        HttpStatus.NOT_FOUND, "Locker not found."
                ));

        if (locker.status != LockerStatus.ACTIVE) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT, "Locker is disabled."
            );
        }

        String normalizedRoom = room.strip();
        List<Account> residents =
                accounts.findByCommunityAndRoomAndRole(
                        locker.community,
                        normalizedRoom,
                        Account.Role.RESIDENT
                );

        if (residents.isEmpty()) {
            throw AccountService.fail(
                    HttpStatus.NOT_FOUND, "Room not found."
            );
        }

        List<Recipient> approved = residents.stream()
                .filter(a -> a.status == Account.Status.APPROVED)
                .map(a -> new Recipient(a.id, mask(a.name)))
                .toList();

        if (approved.isEmpty()) {
            throw AccountService.fail(
                    HttpStatus.CONFLICT,
                    "No approved resident for this room."
            );
        }

        return approved;
    }

    private String mask(String name) {
        int firstEnd = name.offsetByCodePoints(0, 1);
        int remaining = name.codePointCount(0, name.length()) - 1;
        return name.substring(0, firstEnd)
                + "*".repeat(Math.max(1, remaining));
    }
}
