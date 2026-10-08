package com.cpms.community.directmessage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DirectAttachmentRepository extends JpaRepository<DirectAttachment, Long> {
    // join fetch: entities use public fields, which read as null on an uninitialized lazy proxy.
    @Query("select a from DirectAttachment a join fetch a.message where a.message.id in :messageIds order by a.id")
    List<DirectAttachment> findByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    @Query("select a from DirectAttachment a join fetch a.message m join fetch m.conversation c join fetch c.resident where a.id = :id")
    java.util.Optional<DirectAttachment> findWithConversation(@Param("id") Long id);
}
