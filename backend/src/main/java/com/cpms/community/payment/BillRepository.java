package com.cpms.community.payment;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface BillRepository extends JpaRepository<Bill,Long>{
 List<Bill> findByCommunityOrderByCreatedAtDesc(String community);
 List<Bill> findByCommunityAndResidentIdOrderByCreatedAtDesc(String community,Long residentId);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select b from Bill b where b.id=:id and b.community=:community")
 Optional<Bill> lock(@Param("id") Long id,@Param("community") String community);
}
