package com.cpms.community;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ReviewRepository extends JpaRepository<ReviewEvent, Long> {
    List<ReviewEvent> findByAccountIdOrderByCreatedAtDesc(Long id);
}
