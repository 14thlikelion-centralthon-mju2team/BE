package com.hq.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "engine_config")
@Getter
@NoArgsConstructor
public class EngineConfigEntry {
    @Id
    private String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode configValue;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private Instant updatedAt;

    private String updatedBy;
}
