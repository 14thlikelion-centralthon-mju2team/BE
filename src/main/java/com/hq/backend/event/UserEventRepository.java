package com.hq.backend.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEventRepository extends JpaRepository<UserEvent, UUID> {

    List<UserEvent> findByUserIdOrderByStartsAtAsc(UUID userId);

    Optional<UserEvent> findByIdAndUserId(UUID id, UUID userId);
}
