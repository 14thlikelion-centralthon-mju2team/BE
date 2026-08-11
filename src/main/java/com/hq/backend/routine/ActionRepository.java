package com.hq.backend.routine;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionRepository extends JpaRepository<Action, UUID> {

    List<Action> findByIdIn(List<UUID> ids);
}
