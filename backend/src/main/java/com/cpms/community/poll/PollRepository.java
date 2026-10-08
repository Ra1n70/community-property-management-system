package com.cpms.community.poll;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface PollRepository extends JpaRepository<Poll, Long> {
    @Query("select distinct p from Poll p left join fetch p.options where p.community = :community order by p.createdAt desc, p.id desc")
    List<Poll> findWithOptions(@Param("community") String community);
    Optional<Poll> findByIdAndCommunity(Long id, String community);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Poll p where p.id = :id and p.community = :community")
    Optional<Poll> lock(@Param("id") Long id, @Param("community") String community);
}
