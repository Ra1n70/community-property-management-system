package com.cpms.community.announcement.repository;
import com.cpms.community.announcement.model.Announcement;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findByCommunityOrderByPublishedAtDescIdDesc(String community);
    Optional<Announcement> findByIdAndCommunity(Long id, String community);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Announcement a where a.id=:id and a.community=:community")
    Optional<Announcement> lock(@Param("id") Long id,@Param("community") String community);
}
