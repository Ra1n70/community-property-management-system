package com.cpms.community.directmessage;

import com.cpms.community.Account;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {
    List<DirectMessage> findByConversationOrderByCreatedAtAscIdAsc(DirectConversation conversation);
    Optional<DirectMessage> findByConversationAndSenderAndClientRequestId(
            DirectConversation conversation, Account sender, String clientRequestId);
    Optional<DirectMessage> findFirstByConversationOrderByCreatedAtDescIdDesc(DirectConversation conversation);
    @EntityGraph(attributePaths = "sender")
    List<DirectMessage> findByConversationOrderByIdDesc(DirectConversation conversation, Pageable pageable);
    @EntityGraph(attributePaths = "sender")
    List<DirectMessage> findByConversationAndIdLessThanOrderByIdDesc(
            DirectConversation conversation, Long beforeId, Pageable pageable);
    Optional<DirectMessage> findByIdAndConversation(Long id, DirectConversation conversation);

    @Query("""
            select count(m) from DirectMessage m
            where m.conversation = :conversation
              and m.sender.role = com.cpms.community.Account.Role.MANAGER
              and (:cursor is null or m.id > :cursor)
            """)
    long countResidentUnread(@Param("conversation") DirectConversation conversation, @Param("cursor") Long cursor);

    @Query("""
            select count(m) from DirectMessage m
            where m.conversation = :conversation
              and m.sender = :resident
              and (:cursor is null or m.id > :cursor)
            """)
    long countManagerUnread(@Param("conversation") DirectConversation conversation,
                            @Param("resident") Account resident, @Param("cursor") Long cursor);

    /** Unread resident messages per conversation of a community, in one query: rows of [conversationId, count]. */
    @Query("""
            select c.id, count(m) from DirectMessage m join m.conversation c
            where c.community = :community and m.sender = c.resident
              and (c.managerLastReadMessageId is null or m.id > c.managerLastReadMessageId)
              and c.id in :conversationIds
            group by c.id
            """)
    List<Object[]> managerUnreadByConversation(@Param("community") String community,
                                               @Param("conversationIds") java.util.Collection<Long> conversationIds);

    @Query("""
            select count(m) from DirectMessage m join m.conversation c
            where c.community = :community and m.sender = c.resident
              and (c.managerLastReadMessageId is null or m.id > c.managerLastReadMessageId)
            """)
    long managerUnreadTotal(@Param("community") String community);
}
