package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FlywayUpgradeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Test
    void upgradesVersionTwoDataAndInvalidatesExistingProcessingClaims() throws Exception {
        flywayAt("2").migrate();
        UUID assetId = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID received = UUID.randomUUID();
        UUID processingWithoutJob = UUID.randomUUID();
        UUID processingWithJob = UUID.randomUUID();
        UUID duplicateAssetId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertAsset(connection, assetId);
            insertAsset(connection, duplicateAssetId);
            insertScan(connection, completed, assetId, "COMPLETED", "a".repeat(64));
            insertScan(connection, failed, assetId, "FAILED", "b".repeat(64));
            insertScan(connection, received, assetId, "RECEIVED", "c".repeat(64));
            insertScan(connection, processingWithoutJob, assetId, "PROCESSING", "d".repeat(64));
            insertScan(connection, processingWithJob, assetId, "PROCESSING", "e".repeat(64));
        }

        flywayAt("3").migrate();
        UUID existingJob = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ingestion_jobs (
                            id, scan_id, payload_key, status, attempt_count, max_attempts,
                            available_at, locked_at, created_at, updated_at
                        )
                        VALUES (?, ?, ?, 'PROCESSING', 1, 3, ?, ?, ?, ?)
                        """)) {
            Instant now = Instant.now();
            statement.setObject(1, existingJob);
            statement.setObject(2, processingWithJob);
            statement.setString(3, processingWithJob + "/existing.json");
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            statement.setObject(5, now.atOffset(ZoneOffset.UTC));
            statement.setObject(6, now.atOffset(ZoneOffset.UTC));
            statement.setObject(7, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }

        Flyway latest = flywayAt(null);
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(scanStatus(completed)).isEqualTo("COMPLETED");
        assertThat(scanStatus(failed)).isEqualTo("FAILED");
        assertThat(scanStatus(received)).isEqualTo("FAILED");
        assertThat(scanStatus(processingWithoutJob)).isEqualTo("FAILED");
        assertThat(scanStatus(processingWithJob)).isEqualTo("RECEIVED");
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT status, locked_at, claim_token
                        FROM ingestion_jobs
                        WHERE id = ?
                        """)) {
            statement.setObject(1, existingJob);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("RETRY_WAIT");
                assertThat(result.getObject("locked_at")).isNull();
                assertThat(result.getObject("claim_token")).isNull();
            }
        }
        assertThat(countActiveScansWithoutJobs()).isZero();
        assertThat(assetExternalReference(assetId)).isEqualTo("legacy:1");
        assertThat(assetExternalReference(duplicateAssetId)).isNull();
    }

    private Flyway flywayAt(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private void insertAsset(Connection connection, UUID assetId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO assets (id, name, type, external_reference, created_at, updated_at)
                VALUES (?, 'legacy', 'CONTAINER_IMAGE', 'legacy:1', ?, ?)
                """)) {
            Instant now = Instant.now();
            statement.setObject(1, assetId);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.setObject(3, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void insertScan(
            Connection connection,
            UUID scanId,
            UUID assetId,
            String status,
            String contentHash) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scans (
                    id, asset_id, scanner, status, started_at, completed_at,
                    received_at, source_file_name, content_hash, failure_reason
                )
                VALUES (?, ?, 'TRIVY', ?, ?, ?, ?, 'legacy.json', ?, ?)
                """)) {
            Instant now = Instant.now();
            statement.setObject(1, scanId);
            statement.setObject(2, assetId);
            statement.setString(3, status);
            statement.setObject(4, status.equals("PROCESSING") ? now.atOffset(ZoneOffset.UTC) : null);
            statement.setObject(
                    5,
                    status.equals("COMPLETED") || status.equals("FAILED")
                            ? now.atOffset(ZoneOffset.UTC)
                            : null);
            statement.setObject(6, now.atOffset(ZoneOffset.UTC));
            statement.setString(7, contentHash);
            statement.setString(8, status.equals("FAILED") ? "Legacy failure" : null);
            statement.executeUpdate();
        }
    }

    private String scanStatus(UUID scanId) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("SELECT status FROM scans WHERE id = ?")) {
            statement.setObject(1, scanId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private int countActiveScansWithoutJobs() throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM scans AS scan
                        WHERE scan.status IN ('RECEIVED', 'PROCESSING')
                          AND NOT EXISTS (
                              SELECT 1 FROM ingestion_jobs AS job WHERE job.scan_id = scan.id
                          )
                        """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private String assetExternalReference(UUID assetId) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT external_reference FROM assets WHERE id = ?")) {
            statement.setObject(1, assetId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }
}
