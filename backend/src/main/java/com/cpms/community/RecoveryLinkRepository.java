package com.cpms.community;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RecoveryLinkRepository extends JpaRepository<RecoveryLink, Long> {
    Optional<RecoveryLink> findByTokenHash(String tokenHash);
    List<RecoveryLink> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
