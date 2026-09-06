package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.vulnflow.asset.*;
import com.vulnflow.scan.*;
import com.vulnflow.ui.auth.*;
import com.vulnflow.ui.scan.*;
import com.vulnflow.ui.target.*;
import com.vulnflow.jobs.UiScanReconciliationJob;
import com.vulnflow.processing.port.*;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"vulnflow.security.api-key.value=test-api-key", "vulnflow.worker.enabled=false",
        "vulnflow.ui.enabled=false"})
@Testcontainers
class UiScanReconciliationIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Autowired AssetRepository assets;
    @Autowired ScanRepository scans;
    @Autowired UiUserRepository users;
    @Autowired UiTargetRepository targets;
    @Autowired UiAgentRepository agents;
    @Autowired UiScanRequestRepository requests;
    @Autowired UiScanRequestService service;
    @MockitoBean ProcessingResultReader results;

    @Test
    void localCompletionIsPersistedWithoutOpeningTheDetail() {
        UiScanRequest request = processingRequest(true);
        new UiScanReconciliationJob(requests, service).reconcile();
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(UiScanRequestStatus.COMPLETED);
    }

    @Test
    void cloudCompletionRecoversAfterTemporaryReadFailure() {
        UiScanRequest request = processingRequest(false);
        UUID scanId = request.getScan().getId();
        ProcessingResultSummary summary = mock(ProcessingResultSummary.class);
        when(summary.status()).thenReturn(ProcessingResultStatus.COMPLETED);
        when(results.findScan(scanId)).thenThrow(new IllegalStateException("Temporary failure"))
                .thenReturn(Optional.of(summary));
        UiScanReconciliationJob job = new UiScanReconciliationJob(requests, service);
        job.reconcile();
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(UiScanRequestStatus.PROCESSING);
        job.reconcile();
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(UiScanRequestStatus.COMPLETED);
    }

    @Test
    void cloudFailureBecomesTerminalWithoutBrowserPolling() {
        UiScanRequest request = processingRequest(false);
        ProcessingResultSummary summary = mock(ProcessingResultSummary.class);
        when(summary.status()).thenReturn(ProcessingResultStatus.FAILED);
        when(summary.safeError()).thenReturn("Report validation failed");
        when(results.findScan(request.getScan().getId())).thenReturn(Optional.of(summary));
        new UiScanReconciliationJob(requests, service).reconcile();
        UiScanRequest stored = requests.findById(request.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(UiScanRequestStatus.FAILED);
        assertThat(stored.getSafeError()).isEqualTo("Report validation failed");
    }

    private UiScanRequest processingRequest(boolean completed) {
        String identity = UUID.randomUUID().toString();
        Asset asset = assets.save(new Asset(identity, AssetType.CONTAINER_IMAGE, "test:" + identity));
        Scan scan = new Scan(asset, ScannerType.TRIVY, "test.json", "a".repeat(64));
        if (completed) scan.markCompleted("test");
        scan = scans.save(scan);
        UiUser user = users.save(new UiUser(identity, "unused-test-hash", UiRole.OPERATOR, false));
        UiTarget target = targets.save(new UiTarget(identity, "test:" + identity, asset, user));
        UiAgent agent = agents.save(new UiAgent(identity));
        UiScanRequest request = new UiScanRequest(target, user);
        UUID token = request.claim(agent, Duration.ofMinutes(2));
        request.start(token, Duration.ofMinutes(2));
        request.processing(token, scan, UUID.randomUUID());
        requests.save(request);
        return request;
    }
}
