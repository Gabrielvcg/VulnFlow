package com.vulnflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class CommandUploadMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Test
    void upgradesV10WithLegacyRowsEnforcesIdentityAndRestoresDisposableBackup() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .locations("classpath:db/migration").target("10").load().migrate();
        UUID asset = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        UUID scan = UUID.randomUUID();
        String hash = "a".repeat(64);
        try (Connection c = connection(POSTGRES.getJdbcUrl()); Statement sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO assets(id,name,type,external_reference,created_at,updated_at) VALUES ('%s','migration','CONTAINER_IMAGE','migration:1',now(),now())".formatted(asset));
            sql.executeUpdate("INSERT INTO ui_users(id,username,password_hash,role,created_at,updated_at) VALUES ('%s','migration','fixture-hash','OPERATOR',now(),now())".formatted(user));
            sql.executeUpdate("INSERT INTO ui_targets(id,name,type,external_reference,asset_id,created_at,updated_at) VALUES ('%s','migration','CONTAINER_IMAGE','migration:1','%s',now(),now())".formatted(target,asset));
            sql.executeUpdate("INSERT INTO scans(id,asset_id,scanner,status,received_at,source_file_name,content_hash) VALUES ('%s','%s','TRIVY','RECEIVED',now(),'original.json','%s')".formatted(scan,asset,hash));
            sql.executeUpdate("INSERT INTO ui_scan_requests(id,target_id,requested_by,status,requested_at,updated_at) VALUES ('%s','%s','%s','REQUESTED',now(),now())".formatted(pending,target,user));
            sql.executeUpdate("INSERT INTO ui_scan_requests(id,target_id,requested_by,status,scan_id,uploaded_at,requested_at,updated_at) VALUES ('%s','%s','%s','PROCESSING','%s',now(),now(),now())".formatted(accepted,target,user,scan));
        }
        Flyway latest = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        latest.migrate();
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("11");
        try (Connection c = connection(POSTGRES.getJdbcUrl()); Statement sql = c.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT claim_token,claim_expires_at,upload_asset_id,upload_content_hash,upload_attempt_id FROM ui_scan_requests ORDER BY id")) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    for (int column=1; column<=5; column++) assertThat(rows.getObject(column)).isNull();
                }
                assertThat(count).isEqualTo(2);
            }
            assertSqlState(sql,"UPDATE ui_scan_requests SET upload_asset_id='%s', upload_content_hash='%s' WHERE id='%s'".formatted(UUID.randomUUID(),hash,pending),"23503");
            assertSqlState(sql,"UPDATE ui_scan_requests SET upload_asset_id='%s', upload_content_hash='short' WHERE id='%s'".formatted(asset,pending),"23514");
            assertSqlState(sql,"UPDATE ui_scan_requests SET upload_content_hash='%s' WHERE id='%s'".formatted(hash,pending),"23514");
            assertSqlState(sql,"UPDATE ui_scan_requests SET upload_attempt_id='%s' WHERE id='%s'".formatted(UUID.randomUUID(),pending),"23514");
            // Old application column projections and inserts still work with V11's nullable additions.
            UUID oldStyle = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO ui_scan_requests(id,target_id,requested_by,status,requested_at,updated_at) VALUES ('%s','%s','%s','REQUESTED',now(),now())".formatted(oldStyle,target,user));
            UUID token = UUID.randomUUID();
            sql.executeUpdate("UPDATE ui_scan_requests SET claim_token='%s',claim_expires_at=NULL,upload_asset_id='%s',upload_content_hash='%s' WHERE id='%s'".formatted(token,asset,hash,accepted));
            try (ResultSet row = sql.executeQuery("SELECT status,scan_id,claim_token,claim_expires_at,uploaded_at FROM ui_scan_requests WHERE id='%s'".formatted(accepted))) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("PROCESSING");
                assertThat(row.getObject("scan_id",UUID.class)).isEqualTo(scan);
                assertThat(row.getObject("claim_token",UUID.class)).isEqualTo(token);
                assertThat(row.getObject("claim_expires_at")).isNull();
                assertThat(row.getObject("uploaded_at")).isNotNull();
            }
        }
        assertExec("pg_dump","-U",POSTGRES.getUsername(),"-d",POSTGRES.getDatabaseName(),"-Fc","-f","/tmp/v11-fixture.dump");
        assertExec("createdb","-U",POSTGRES.getUsername(),"vulnflow_restore");
        assertExec("pg_restore","-U",POSTGRES.getUsername(),"--exit-on-error","-d","vulnflow_restore","/tmp/v11-fixture.dump");
        String restoredUrl = POSTGRES.getJdbcUrl().replace("/"+POSTGRES.getDatabaseName(),"/vulnflow_restore");
        try (Connection c = connection(restoredUrl); Statement sql = c.createStatement()) {
            try (ResultSet row = sql.executeQuery("SELECT count(*) FROM ui_scan_requests")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(3);
            }
            try (ResultSet row = sql.executeQuery("SELECT upload_content_hash,claim_token,claim_expires_at FROM ui_scan_requests WHERE id='%s'".formatted(accepted))) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo(hash);
                assertThat(row.getObject(2)).isNotNull(); assertThat(row.getObject(3)).isNull();
            }
        }
        Flyway.configure().dataSource(restoredUrl,POSTGRES.getUsername(),POSTGRES.getPassword())
                .locations("classpath:db/migration").load().validate();
    }

    private void assertSqlState(Statement sql,String command,String state) {
        assertThatThrownBy(() -> sql.executeUpdate(command)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException)error).getSQLState()).isEqualTo(state));
    }
    private void assertExec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as("Disposable PostgreSQL command: %s; stderr: %s",command[0],result.getStderr()).isZero();
    }
    private Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url,POSTGRES.getUsername(),POSTGRES.getPassword());
    }
}
