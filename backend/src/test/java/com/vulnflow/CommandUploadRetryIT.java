package com.vulnflow;

import static org.assertj.core.api.Assertions.*;
import com.vulnflow.asset.*;
import com.vulnflow.scan.*;
import com.vulnflow.ui.auth.*;
import com.vulnflow.ui.scan.*;
import com.vulnflow.ui.target.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Runs in the disposable PostgreSQL fixture in the full CI verification reactor. */
@SpringBootTest(properties = {"vulnflow.security.api-key.value=test-api-key", "vulnflow.worker.enabled=false",
        "vulnflow.ui.enabled=false", "vulnflow.ui.scans-enabled=true"})
@Testcontainers
class CommandUploadRetryIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Autowired UiScanRequestRepository requests;
    @Autowired UiTargetRepository targets;
    @Autowired UiUserRepository users;
    @Autowired UiAgentRepository agents;
    @Autowired AssetRepository assets;
    @Autowired ScanRepository scans;
    @Autowired UiScanRequestService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void oneConcurrentReservationWinsAndAcceptedIdentitySurvivesReloadAndFailure() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<UiScanRequest.UploadReservation> attempt = () -> {
            ready.countDown();
            if (!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("Start timeout");
            try { return service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash()); }
            catch (IllegalStateException concurrent) {
                assertThat(concurrent).hasMessage("The command upload is still in progress");
                return null;
            }
        };
        UiScanRequest.UploadReservation reservation;
        try {
            Future<UiScanRequest.UploadReservation> first = executor.submit(attempt);
            Future<UiScanRequest.UploadReservation> second = executor.submit(attempt);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var a = first.get(10,TimeUnit.SECONDS);
            var b = second.get(10,TimeUnit.SECONDS);
            assertThat((a == null) != (b == null)).isTrue();
            reservation = a == null ? b : a;
        } finally { go.countDown(); executor.shutdownNow(); }
        UUID scanId = new TransactionTemplate(transactions).execute(status -> scans.saveAndFlush(
                new Scan(assets.getReferenceById(fixture.assetId()),ScannerType.TRIVY,"original.json",fixture.hash())).getId());
        UUID event = UUID.randomUUID();
        service.associateUpload(fixture.id(),fixture.token(),reservation.attemptId(),scanId,event);
        var before = jdbc.queryForMap("SELECT * FROM ui_scan_requests WHERE id=?",fixture.id());
        assertThat(service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash()).acceptedScanId()).isEqualTo(scanId);
        service.associateUpload(fixture.id(),fixture.token(),reservation.attemptId(),scanId,event);
        assertThat(jdbc.queryForMap("SELECT * FROM ui_scan_requests WHERE id=?",fixture.id())).isEqualTo(before);
        assertThat(before.get("claim_expires_at")).isNull();
        assertThat(before.get("claim_token")).isEqualTo(fixture.token());
        new TransactionTemplate(transactions).executeWithoutResult(status -> requests.findByIdForUpdate(fixture.id()).orElseThrow().fail(null,"post-processing failure"));
        assertThat(service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash()).acceptedScanId()).isEqualTo(scanId);
        assertThatThrownBy(() -> service.start(fixture.agent(),fixture.id(),fixture.token())).isInstanceOf(StaleScanClaimException.class);
        assertThatThrownBy(() -> service.fail(fixture.agent(),fixture.id(),fixture.token(),"obsolete")).isInstanceOf(StaleScanClaimException.class);
    }

    @Test
    void releaseChecksPersistedAttemptAndRetainsIdentityAfterReload() {
        Fixture fixture = fixture();
        var first = service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash());
        service.releaseUpload(fixture.id(),fixture.token(),first.attemptId());
        assertThatThrownBy(() -> service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),"b".repeat(64)))
                .isInstanceOf(StaleScanClaimException.class);
        var second = service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash());
        service.releaseUpload(fixture.id(),fixture.token(),first.attemptId());
        assertThat(jdbc.queryForObject("SELECT upload_attempt_id FROM ui_scan_requests WHERE id=?",UUID.class,fixture.id())).isEqualTo(second.attemptId());
    }

    @Test
    void reservationRollbackLeavesNoIdentityOrExecutionSlotAndAllowsRetry() {
        Fixture fixture = fixture();
        var before = jdbc.queryForMap("SELECT * FROM ui_scan_requests WHERE id=?",fixture.id());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash());
            requests.flush();
            assertThat(jdbc.queryForObject("SELECT upload_content_hash FROM ui_scan_requests WHERE id=?",String.class,fixture.id()))
                    .isEqualTo(fixture.hash());
            throw new IllegalStateException("Rollback reservation");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Rollback reservation");
        assertThat(jdbc.queryForMap("SELECT * FROM ui_scan_requests WHERE id=?",fixture.id())).isEqualTo(before);
        var reserved = service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash());
        assertThat(reserved.attemptId()).isNotNull();
    }

    @Test
    void expiredReservedClaimRecoversWithFreshTokenAndCannotBeReleasedByOldOperation() {
        Fixture fixture = fixture();
        var old = service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash());
        jdbc.update("UPDATE ui_scan_requests SET claim_expires_at=now()-interval '1 minute' WHERE id=?",fixture.id());
        UUID fresh = new TransactionTemplate(transactions).execute(status -> {
            UiScanRequest request = requests.findByIdForUpdate(fixture.id()).orElseThrow();
            assertThat(request.recover(Duration.ofHours(1),2)).isTrue();
            UUID next = request.claim(agents.getReferenceById(fixture.agent()),Duration.ofMinutes(2));
            request.start(next,Duration.ofMinutes(2));
            return next;
        });
        var next = service.reserveUpload(fixture.id(),fresh,fixture.assetId(),"b".repeat(64));
        service.releaseUpload(fixture.id(),fixture.token(),old.attemptId());
        assertThat(jdbc.queryForObject("SELECT upload_attempt_id FROM ui_scan_requests WHERE id=?",UUID.class,fixture.id()))
                .isEqualTo(next.attemptId());
        assertThatThrownBy(() -> service.reserveUpload(fixture.id(),fixture.token(),fixture.assetId(),fixture.hash()))
                .isInstanceOf(StaleScanClaimException.class);
    }
    private Fixture fixture() {
        return new TransactionTemplate(transactions).execute(status -> {
            String key = UUID.randomUUID().toString();
            UiUser user = users.save(new UiUser("upload-"+key,"hash",UiRole.OPERATOR,false));
            Asset asset = assets.save(new Asset("upload-"+key,AssetType.CONTAINER_IMAGE,"upload:"+key));
            UiTarget target = targets.save(new UiTarget("upload",asset.getExternalReference(),asset,user));
            String agentId = "upload-"+key;
            UiAgent agent = agents.save(new UiAgent(agentId));
            UiScanRequest request = new UiScanRequest(target,user);
            UUID token = request.claim(agent,Duration.ofMinutes(2));
            request.start(token,Duration.ofMinutes(2));
            requests.saveAndFlush(request);
            return new Fixture(request.getId(),token,asset.getId(),"a".repeat(64),agentId);
        });
    }
    private record Fixture(UUID id, UUID token, UUID assetId, String hash, String agent) {}
}
