package com.cpms.community.maintenance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MaintenanceEventRepository extends JpaRepository<MaintenanceEvent,Long> {
    List<MaintenanceEvent> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);
}
