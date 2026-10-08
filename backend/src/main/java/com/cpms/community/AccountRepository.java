package com.cpms.community;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmail(String email);
    List<Account> findByCommunityAndRoleOrderBySubmittedAtDesc(String community, Account.Role role);
    long countByCommunityAndRoleAndStatus(String community, Account.Role role, Account.Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") Long id);
    List<Account> findByCommunityAndRoomAndRoleAndStatus(
            String community,
            String room,
            Account.Role role,
            Account.Status status
    );
    List<Account> findByCommunityAndRoomAndRole(
            String community,
            String room,
            Account.Role role
    );
    @Query("""
    select a from Account a
    where a.community = :community
      and a.role = :role
      and a.status = :status
      and (
          lower(a.name) like lower(concat('%', :keyword, '%'))
          or lower(a.room) like lower(concat('%', :keyword, '%'))
      )
    order by a.name
    """)
    List<Account> searchResidents(
            @Param("community") String community,
            @Param("role") Account.Role role,
            @Param("status") Account.Status status,
            @Param("keyword") String keyword,
            org.springframework.data.domain.Pageable pageable
    );
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Account a where a.community = :community and a.role = com.cpms.community.Account.Role.MANAGER order by a.id")
    java.util.List<Account> lockCommunityManagers(@org.springframework.data.repository.query.Param("community") String community);
}
