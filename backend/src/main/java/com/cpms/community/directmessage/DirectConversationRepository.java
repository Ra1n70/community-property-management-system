package com.cpms.community.directmessage;

import com.cpms.community.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DirectConversationRepository extends JpaRepository<DirectConversation, Long> {
    Optional<DirectConversation> findByResident(Account resident);
    Optional<DirectConversation> findByIdAndCommunity(Long id, String community);
    @EntityGraph(attributePaths = "resident")
    List<DirectConversation> findByCommunityOrderByCreatedAtDescIdDesc(String community);
    List<DirectConversation> findByLastMessageIdIsNull();

    /**
     * The manager inbox in one query: newest conversation first, optionally matching a name, room or the latest
     * message (pattern is a LIKE pattern, ignored when all is true) and optionally only those with unread messages.
     */
    @Query("""
            select c from DirectConversation c join fetch c.resident r
            where c.community = :community and c.lastMessageId is not null
              and (:all = true or lower(r.name) like :pattern escape '\\'
                   or lower(coalesce(r.room, '')) like :pattern escape '\\'
                   or lower(coalesce(c.lastMessagePreview, '')) like :pattern escape '\\')
              and (:unreadOnly = false or exists (select 1 from DirectMessage m where m.conversation = c
                   and m.sender = c.resident and (c.managerLastReadMessageId is null or m.id > c.managerLastReadMessageId)))
            order by c.lastMessageAt desc, c.id desc
            """)
    List<DirectConversation> inbox(@Param("community") String community, @Param("all") boolean all,
                                   @Param("pattern") String pattern, @Param("unreadOnly") boolean unreadOnly, Pageable page);
}
