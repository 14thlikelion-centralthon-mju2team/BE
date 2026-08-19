package com.hq.backend.config;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EngineConfigRepository extends JpaRepository<EngineConfigEntry, String> {
    List<EngineConfigEntry> findByConfigKeyIn(Collection<String> configKeys);
}
