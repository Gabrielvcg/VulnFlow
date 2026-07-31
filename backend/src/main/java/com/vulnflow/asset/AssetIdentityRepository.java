package com.vulnflow.asset;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssetIdentityRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AssetIdentityRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(
            UUID id,
            String name,
            AssetType type,
            String externalReference) {
        Instant now = Instant.now();
        int inserted = jdbcTemplate.update("""
                INSERT INTO assets (id, name, type, external_reference, created_at, updated_at)
                VALUES (:id, :name, :type, :externalReference, :createdAt, :updatedAt)
                ON CONFLICT (type, external_reference) DO NOTHING
                """, Map.of(
                "id", id,
                "name", name,
                "type", type.name(),
                "externalReference", externalReference,
                "createdAt", Timestamp.from(now),
                "updatedAt", Timestamp.from(now)));
        return inserted == 1;
    }
}
