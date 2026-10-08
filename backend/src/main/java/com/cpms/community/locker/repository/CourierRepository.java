package com.cpms.community.locker.repository;

import com.cpms.community.locker.entity.Courier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CourierRepository extends JpaRepository<Courier, Long> {
    Optional<Courier> findByCodeHash(String codeHash);
    boolean existsByCodeHash(String codeHash);
    List<Courier> findByCommunityOrderByCreatedAtDesc(String community);
    List<Courier> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    Optional<Courier> findByIdAndCommunity(Long id, String community);
}
