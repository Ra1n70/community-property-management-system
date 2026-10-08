package com.cpms.community.residentchat;

import com.cpms.community.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ResidentConversationRepository extends JpaRepository<ResidentConversation, Long> {
    Optional<ResidentConversation> findByResidentOneAndResidentTwo(Account residentOne, Account residentTwo);
    List<ResidentConversation> findByResidentOneOrResidentTwo(Account residentOne, Account residentTwo);
    Optional<ResidentConversation> findByIdAndCommunity(Long id, String community);
}
