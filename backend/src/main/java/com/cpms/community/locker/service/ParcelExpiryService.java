package com.cpms.community.locker.service;

import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.entity.PickupCredential;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.locker.enums.PickupCredentialStatus;
import com.cpms.community.locker.repository.ParcelRepository;
import com.cpms.community.locker.repository.PickupCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hibernate.Hibernate;
import java.time.Instant;
import java.util.List;

@Service
public class ParcelExpiryService {
    private final ParcelRepository parcels;
    private final PickupCredentialRepository credentials;

    public ParcelExpiryService(
            ParcelRepository parcels,
            PickupCredentialRepository credentials
    ) {
        this.parcels = parcels;
        this.credentials = credentials;
    }

    @Transactional
    public boolean expireOne(Long parcelId, Instant now) {
        // 与凭码取件保持相同顺序：先锁取件凭证，再处理包裹。
        List<PickupCredential> active = credentials
                .lockByParcelIdAndStatus(
                        parcelId, PickupCredentialStatus.ACTIVE
                );

        Parcel locked = parcels.lockById(parcelId).orElse(null);
        Parcel parcel = locked == null
                ? null
                : Hibernate.unproxy(locked, Parcel.class);
        if (parcel == null
                || parcel.status != ParcelStatus.PENDING_PICKUP
                || now.isBefore(parcel.expiresAt)) {
            return false;
        }

        parcel.status = ParcelStatus.EXPIRED;
        for (PickupCredential credential : active) {
            Hibernate.unproxy(credential, PickupCredential.class).status =
                    PickupCredentialStatus.EXPIRED;
        }

        // 格口仍是 OCCUPIED，等物业实际取回后才释放。
        return true;
    }
}
