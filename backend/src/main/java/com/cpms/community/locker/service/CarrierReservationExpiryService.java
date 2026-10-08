package com.cpms.community.locker.service;

import com.cpms.community.locker.entity.CarrierIntakeSession;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.repository.CarrierIntakeSessionRepository;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CarrierReservationExpiryService {
    private final CarrierIntakeSessionRepository sessions;

    public CarrierReservationExpiryService(
            CarrierIntakeSessionRepository sessions
    ) {
        this.sessions = sessions;
    }

    @Transactional
    public boolean expireOne(Long sessionId, Instant now) {
        CarrierIntakeSession session = sessions.lockById(sessionId)
                .orElse(null);

        if (session == null
                || session.completedAt != null
                || session.expiredAt != null
                || now.isBefore(session.expiresAt)) {
            return false;
        }

        LockerCell cell = Hibernate.unproxy(
                session.cell, LockerCell.class
        );

        // 只释放仍由这个存件流程预留的格口；不碰已占用格口。
        if (cell.status == CellStatus.RESERVED) {
            cell.status = CellStatus.AVAILABLE;
        }

        session.expiredAt = now;
        return true;
    }
}
