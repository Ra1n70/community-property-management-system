package com.cpms.community.support;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SupportFaqRepository extends JpaRepository<SupportFaq, Long> {
    List<SupportFaq> findByCommunityOrderByPositionAscIdAsc(String community);
    Optional<SupportFaq> findByIdAndCommunity(Long id, String community);
}
