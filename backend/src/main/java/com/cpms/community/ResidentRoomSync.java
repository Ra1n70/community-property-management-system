package com.cpms.community;

import com.cpms.community.maintenance.MaintenanceTicket;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current resident labels change together; room-change audit history and work locations stay intact.
 * Finished maintenance tickets (completed, rejected, closed unresolved) keep the room where the work was requested.
 */
@Service
public class ResidentRoomSync {
    private static final java.util.List<MaintenanceTicket.Status> FINISHED=java.util.List.of(
        MaintenanceTicket.Status.COMPLETED,MaintenanceTicket.Status.REJECTED,MaintenanceTicket.Status.UNRESOLVED);
    private final EntityManager entities;
    public ResidentRoomSync(EntityManager entities) { this.entities=entities; }
    @Transactional
    public void update(Long accountId,String community,String room) {
        for(String entity:java.util.List.of("MaintenanceTicket","Bill","Reservation")) {
            String owner=entity.equals("Reservation")?"accountId":"residentId";
            boolean tickets=entity.equals("MaintenanceTicket");
            var query=entities.createQuery("update "+entity+" r set r.room=:room, r.version=r.version+1 where r."+owner+
                "=:owner and r.community=:community and (r.room is null or r.room<>:room)"+(tickets?" and r.status not in :finished":""))
                .setParameter("room",room).setParameter("owner",accountId).setParameter("community",community);
            if(tickets)query.setParameter("finished",FINISHED);
            query.executeUpdate();
        }
    }
}
