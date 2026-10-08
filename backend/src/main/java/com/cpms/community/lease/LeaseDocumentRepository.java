package com.cpms.community.lease;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LeaseDocumentRepository extends JpaRepository<LeaseDocument, Long> {
    List<LeaseDocument> findByCommunityAndResidentIdOrderByUploadedAtDesc(String community, Long residentId);
    List<LeaseDocument> findByCommunityOrderByUploadedAtDesc(String community);
    Optional<LeaseDocument> findByIdAndCommunity(Long id, String community);
}
