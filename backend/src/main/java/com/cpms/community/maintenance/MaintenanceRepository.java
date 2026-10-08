package com.cpms.community.maintenance;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface MaintenanceRepository extends JpaRepository<MaintenanceTicket,Long>, JpaSpecificationExecutor<MaintenanceTicket> {
    List<MaintenanceTicket> findByCommunityOrderByUpdatedAtDesc(String community);
    List<MaintenanceTicket> findByCommunityAndResidentIdOrderByUpdatedAtDesc(String community,Long residentId);
    List<MaintenanceTicket> findByCommunityAndAssigneeIdOrderByUpdatedAtDesc(String community,Long assigneeId);
    Optional<MaintenanceTicket> findByIdAndCommunity(Long id,String community);
    // Filter choices for the manager list, taken from every request rather than the current page.
    @Query("select distinct t.category from MaintenanceTicket t where t.community=:community order by t.category")
    List<String> categories(@Param("community") String community);
    @Query("select t.assigneeId, max(t.assigneeName) from MaintenanceTicket t where t.community=:community and t.assigneeId is not null group by t.assigneeId")
    List<Object[]> assignees(@Param("community") String community);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MaintenanceTicket t where t.id=:id and t.community=:community")
    Optional<MaintenanceTicket> lock(@Param("id") Long id,@Param("community") String community);
}
