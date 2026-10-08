package com.cpms.community.poll;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface PollVoteRepository extends JpaRepository<PollVote, Long> {
    boolean existsByPollIdAndResidentId(Long pollId, Long residentId);
    /** Votes per option for the given polls: rows of (optionId, count). */
    @Query("select v.optionId, count(v) from PollVote v where v.pollId in :polls group by v.optionId")
    List<Object[]> countByOption(@Param("polls") Collection<Long> polls);
    @Query("select v from PollVote v where v.pollId in :polls and v.residentId = :resident")
    List<PollVote> findMine(@Param("polls") Collection<Long> polls, @Param("resident") Long resident);
    long countByPollId(Long pollId);
    void deleteByPollId(Long pollId);
}
