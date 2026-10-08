package com.cpms.community.residentchat;

import com.cpms.community.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ResidentChatMessageRepository extends JpaRepository<ResidentChatMessage, Long> {
    Optional<ResidentChatMessage> findByConversationAndSenderAndClientRequestId(
            ResidentConversation conversation, Account sender, String clientRequestId);
    Optional<ResidentChatMessage> findFirstByConversationOrderByCreatedAtDescIdDesc(ResidentConversation conversation);
    @EntityGraph(attributePaths = "sender")
    List<ResidentChatMessage> findByConversationOrderByIdDesc(ResidentConversation conversation, Pageable page);
    @EntityGraph(attributePaths = "sender")
    List<ResidentChatMessage> findByConversationAndIdLessThanOrderByIdDesc(
            ResidentConversation conversation, Long beforeId, Pageable page);
    Optional<ResidentChatMessage> findByIdAndConversation(Long id, ResidentConversation conversation);
    long countByConversationAndSenderAndIdGreaterThan(ResidentConversation conversation, Account sender, Long cursor);
    long countByConversationAndSender(ResidentConversation conversation, Account sender);
}
