package com.cpms.community;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoomChangeRepository extends JpaRepository<RoomChange,Long> {
    List<RoomChange> findByAccountIdOrderByCreatedAtDesc(Long id);
}
