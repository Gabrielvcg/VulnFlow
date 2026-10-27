package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vulnflow.asset.*;
import com.vulnflow.scan.*;
import com.vulnflow.shared.error.GlobalExceptionHandler;
import com.vulnflow.processing.port.*;
import com.vulnflow.ui.UiProperties;
import com.vulnflow.ui.audit.UiAuditService;
import com.vulnflow.ui.auth.*;
import com.vulnflow.ui.scan.*;
import com.vulnflow.ui.target.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

class CommandUploadRetryTest {
    private final Asset asset = new Asset("demo", AssetType.CONTAINER_IMAGE, "demo:1");
    private final Asset otherAsset = new Asset("other", AssetType.CONTAINER_IMAGE, "other:1");
    private final AssetService assets = mock(AssetService.class);
    private final UiScanRequestRepository requests = mock(UiScanRequestRepository.class);
    private final ScanRepository scans = mock(ScanRepository.class);
    private final ReportStorage storage = mock(ReportStorage.class);
    private final IngestionMetrics metrics = mock(IngestionMetrics.class);
    private final UUID jobId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);
    private final Duration lease = Duration.ofMinutes(2);
    private Scan registered;
    private Runnable duringRegistration = () -> {};
    private int registrations;
    private String failOnce;
    private final ScanSubmissionService submissions = mock(ScanSubmissionService.class, invocation -> {
        if (invocation.getMethod().getName().equals("registerReceived")) {
            registrations++;
            if ("before".equals(failOnce)) {
                failOnce = null;
                throw new IllegalStateException("Registration unavailable before any write");
            }
            Asset receivedAsset = invocation.getArgument(0);
            registered = new Scan(receivedAsset, ScannerType.TRIVY, invocation.getArgument(1), invocation.getArgument(2));
            storage.store(registered.getId(), invocation.getArgument(3));
            duringRegistration.run();
            if ("after".equals(failOnce)) {
                failOnce = null;
                throw new IllegalStateException("Registration commit acknowledgement lost");
            }
            return submission(ScanIngestionOutcome.ACCEPTED);
        }
        if (invocation.getMethod().getName().equals("findRegistered")) {
            if (registered == null) return Optional.empty();
            ScanIngestionOutcome outcome = registered.getStatus() == ScanStatus.COMPLETED
                    ? ScanIngestionOutcome.DUPLICATE : registered.getStatus() == ScanStatus.FAILED
                    ? ScanIngestionOutcome.DEAD_LETTER : registered.getStatus() == ScanStatus.PROCESSING
                    ? ScanIngestionOutcome.ALREADY_PROCESSING : ScanIngestionOutcome.ALREADY_QUEUED;
            return Optional.of(submission(outcome));
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    private UiScanRequest request;
    private UUID token;
    private UiScanRequestService requestService;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        request = new UiScanRequest(new UiTarget("demo", "demo:1", asset, null),
                new UiUser("operator", "hash", UiRole.OPERATOR, false));
        token = request.claim(new UiAgent("agent-a"), lease);
        request.start(token, lease);
        when(assets.requireAsset(asset.getId())).thenReturn(asset);
        when(assets.requireAsset(otherAsset.getId())).thenReturn(otherAsset);
        when(requests.findByIdForUpdate(request.getId())).thenAnswer(i -> Optional.of(request));
        when(scans.findById(any())).thenAnswer(i -> Optional.ofNullable(registered));
        UiProperties properties = mock(UiProperties.class);
        when(properties.claimLease()).thenReturn(lease);
        requestService = new UiScanRequestService(requests, mock(UiTargetRepository.class),
                mock(UiUserRepository.class), mock(UiAgentRepository.class), scans, properties,
                mock(UiAuditService.class), mock(ObjectProvider.class), mock(UiAdmissionLock.class));
        DefaultScanIngestionService ingestion = new DefaultScanIngestionService(assets, submissions,
                new IngestionProperties(DataSize.ofBytes(100), 8000), metrics);
        mvc = MockMvcBuilders.standaloneSetup(new ScanIngestionController(ingestion, requestService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "processing", "completed", "failed"})
    void lostAcknowledgementRecognizesOnlyOriginalUploadWithoutChangingMetadata(String state) throws Exception {
        upload(token, asset.getId(), content, "application/json").andExpect(status().isAccepted());
        assertThat(request.getScan()).isSameAs(registered);
        assertThat(request.getEventId()).isEqualTo(eventId);
        assertThat(request.getClaimExpiresAt()).isNull();
        if (state.equals("processing")) registered.markProcessing();
        if (state.equals("completed")) { registered.markCompleted("1"); request.complete(); }
        if (state.equals("failed")) { registered.markFailed("safe failure"); request.fail(null,"safe failure"); }
        Map<String, Object> before = snapshot();
        int expectedStatus = state.equals("completed") || state.equals("failed") ? 200 : 202;
        String expectedOutcome = state.equals("completed") ? "DUPLICATE" : state.equals("failed")
                ? "DEAD_LETTER" : state.equals("processing") ? "ALREADY_PROCESSING" : "ALREADY_QUEUED";
        upload(token, asset.getId(), content, "application/json; charset=UTF-8")
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.outcome").value(expectedOutcome))
                .andExpect(jsonPath("$.scanId").value(registered.getId().toString()))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(registrations).isEqualTo(1);
        verify(storage, times(1)).store(any(), any());
        verify(storage, never()).delete(any());
        verify(metrics, times(1)).jobAccepted();
        assertThatThrownBy(() -> request.start(token, lease)).isInstanceOf(StaleScanClaimException.class);
        assertThatThrownBy(() -> request.heartbeat(token, lease)).isInstanceOf(StaleScanClaimException.class);
        assertThatThrownBy(() -> request.fail(token, "obsolete")).isInstanceOf(StaleScanClaimException.class);
        assertThatThrownBy(() -> requestService.fail("agent-a", request.getId(), token, "obsolete"))
                .isInstanceOf(StaleScanClaimException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "asset", "bytes"})
    void mismatchedReplayRejectsBeforeAnyAdditionalEffect(String mismatch) throws Exception {
        upload(token, asset.getId(), content, "application/json").andExpect(status().isAccepted());
        Map<String,Object> before = snapshot();
        upload(mismatch.equals("token") ? UUID.randomUUID() : token,
                mismatch.equals("asset") ? otherAsset.getId() : asset.getId(),
                mismatch.equals("bytes") ? "{\"Results\": [ ]}".getBytes(StandardCharsets.UTF_8) : content,
                "application/json").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SCAN_CLAIM"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(registrations).isEqualTo(1);
        verify(storage, times(1)).store(any(), any());
        verify(storage, never()).delete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "old-token", "wrong-token", "wrong-asset"})
    void invalidFirstUploadNeverRegistersOrStores(String failure) throws Exception {
        UUID sentToken = token;
        UUID sentAsset = asset.getId();
        if (failure.equals("expired") || failure.equals("old-token")) {
            ReflectionTestUtils.setField(request,"claimExpiresAt",Instant.now().minusSeconds(1));
        }
        if (failure.equals("old-token")) {
            request.recover(Duration.ofHours(1),2);
            UUID newToken = request.claim(new UiAgent("agent-a"),lease);
            request.start(newToken,lease);
        }
        if (failure.equals("wrong-token")) sentToken = UUID.randomUUID();
        if (failure.equals("wrong-asset")) sentAsset = otherAsset.getId();
        Map<String,Object> before = snapshot();
        upload(sentToken, sentAsset, content, "application/json").andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(registrations).isZero();
        verifyNoInteractions(storage, metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mime", "malformed", "oversize", "empty"})
    void replayStillUsesOriginalValidationBeforeAcknowledging(String invalid) throws Exception {
        upload(token, asset.getId(), content, "application/json").andExpect(status().isAccepted());
        Map<String,Object> before = snapshot();
        byte[] bytes = invalid.equals("oversize") ? new byte[101] : invalid.equals("empty") ? new byte[0] : content;
        String mime = invalid.equals("mime") ? "text/plain" : invalid.equals("malformed") ? "invalid+json" : "application/json";
        upload(token,asset.getId(),bytes,mime).andExpect(status().is(invalid.equals("oversize") ? 413 : invalid.equals("empty") ? 422 : 415));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(registrations).isEqualTo(1);
        verify(storage,times(1)).store(any(),any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentUploadReservationRejectsSameOrDifferentBytesBeforeRegistration(boolean same) throws Exception {
        AtomicBoolean checked = new AtomicBoolean();
        duringRegistration = () -> {
            if (!checked.compareAndSet(false,true)) return;
            try {
                upload(token,asset.getId(),same ? content : "{}".getBytes(StandardCharsets.UTF_8),"application/json")
                        .andExpect(status().is(same ? 500 : 409));
            } catch (Exception e) { throw new AssertionError(e); }
        };
        upload(token,asset.getId(),content,"application/json").andExpect(status().isAccepted());
        assertThat(checked).isTrue();
        assertThat(registrations).isEqualTo(1);
        verify(storage,times(1)).store(any(),any());
        Map<String,Object> before = snapshot();
        requestService.associateUpload(request.getId(),token,registered.getId(),eventId);
        assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(() -> requestService.associateUpload(request.getId(),token,registered.getId(),UUID.randomUUID()))
                .isInstanceOf(StaleScanClaimException.class);
    }

    @Test
    void uploadWithoutCommandKeepsExistingRoute() throws Exception {
        mvc.perform(multipart("/api/v1/scans/trivy").file(file("application/json",content))
                        .param("assetId",asset.getId().toString())).andExpect(status().isAccepted());
        assertThat(registrations).isEqualTo(1);
        verifyNoInteractions(requests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "after"})
    void registrationFailureRetainsIdentityAndAllowsSameClaimRetry(String failure) throws Exception {
        failOnce = failure;
        upload(token,asset.getId(),content,"application/json").andExpect(status().isInternalServerError());
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.RUNNING);
        assertThat(request.getScan()).isNull();
        assertThat(request.getUploadedAt()).isNull();
        assertThat(request.getClaimToken()).isEqualTo(token);
        int writesBefore = registrations;
        upload(token,asset.getId(),"{}".getBytes(StandardCharsets.UTF_8),"application/json")
                .andExpect(status().isConflict());
        assertThat(registrations).isEqualTo(writesBefore);
        upload(token,asset.getId(),content,"application/json").andExpect(status().isAccepted());
        assertThat(registrations).isEqualTo(failure.equals("after") ? 1 : 2);
        verify(storage,times(1)).store(any(),any());
        verify(storage,never()).delete(any());
        assertThat(request.getScan()).isSameAs(registered);
    }

    @Test
    void associationReadFailureRetriesCommittedRegistrationWithoutStoringAgain() throws Exception {
        when(scans.findById(any())).thenThrow(new IllegalStateException("Association read unavailable"))
                .thenAnswer(i -> Optional.of(registered));
        upload(token,asset.getId(),content,"application/json").andExpect(status().isInternalServerError());
        assertThat(request.getScan()).isNull();
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.RUNNING);
        upload(token,asset.getId(),content,"application/json").andExpect(status().isAccepted());
        assertThat(registrations).isEqualTo(1);
        verify(storage,times(1)).store(any(),any());
        verify(storage,never()).delete(any());
    }

    @Test
    void failedOldOperationCannotReleaseARecoveredClaim() throws Exception {
        duringRegistration = () -> {
            ReflectionTestUtils.setField(request,"claimExpiresAt",Instant.now().minusSeconds(1));
            request.recover(Duration.ofHours(1),2);
            UUID fresh = request.claim(new UiAgent("agent-a"),lease);
            request.start(fresh,lease);
            throw new IllegalStateException("Old operation failed after recovery");
        };
        upload(token,asset.getId(),content,"application/json").andExpect(status().isInternalServerError());
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.RUNNING);
        assertThat(request.getClaimToken()).isNotEqualTo(token);
        assertThat(request.getClaimExpiresAt()).isAfter(Instant.now());
        Map<String,Object> before = snapshot();
        upload(token,asset.getId(),content,"application/json").andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        verify(storage,times(1)).store(any(),any());
        verify(storage,never()).delete(any());
    }

    @Test
    void unreadableFileDoesNotReserveTheRequestAndSameClaimCanRetry() throws Exception {
        MockMultipartFile unreadable = new MockMultipartFile("file","report.json","application/json",content) {
            @Override public byte[] getBytes() throws java.io.IOException {
                throw new java.io.IOException("Read unavailable");
            }
        };
        Map<String,Object> before = snapshot();
        mvc.perform(multipart("/api/v1/scans/trivy").file(unreadable)
                .param("assetId",asset.getId().toString()).param("scanRequestId",request.getId().toString())
                .param("claimToken",token.toString())).andExpect(status().isUnprocessableEntity());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(registrations).isZero();
        upload(token,asset.getId(),content,"application/json").andExpect(status().isAccepted());
        verify(storage,times(1)).store(any(),any());
    }
    private ResultActions upload(UUID claim, UUID assetId, byte[] bytes, String type) throws Exception {
        return mvc.perform(multipart("/api/v1/scans/trivy").file(file(type,bytes))
                .param("assetId",assetId.toString()).param("scanRequestId",request.getId().toString())
                .param("claimToken",claim.toString()));
    }
    private MockMultipartFile file(String type, byte[] bytes) { return new MockMultipartFile("file","report.json",type,bytes); }
    private IngestionSubmission submission(ScanIngestionOutcome outcome) {
        return new IngestionSubmission(registered.getId(),jobId,registered.getAsset().getId(),registered.getStatus(),
                registered.getStatus()==ScanStatus.FAILED ? IngestionJobStatus.DEAD_LETTER
                : registered.getStatus()==ScanStatus.COMPLETED ? IngestionJobStatus.COMPLETED
                : registered.getStatus()==ScanStatus.PROCESSING ? IngestionJobStatus.PROCESSING : IngestionJobStatus.PENDING,
                outcome,eventId,"PUBLISHED");
    }
    private Map<String,Object> snapshot() {
        Map<String,Object> values = new HashMap<>();
        for (String field : List.of("status","scan","eventId","claimToken","claimExpiresAt","heartbeatAt",
                "requestedAt","claimedAt","startedAt","uploadedAt","completedAt","updatedAt","safeError","recoveryAttempts")) {
            values.put(field,ReflectionTestUtils.getField(request,field));
        }
        for (String field : List.of("uploadAssetId","uploadContentHash","uploadAttemptId")) {
            if (org.springframework.util.ReflectionUtils.findField(UiScanRequest.class,field) != null) {
                values.put(field,ReflectionTestUtils.getField(request,field));
            }
        }
        if (registered != null) for (String field : List.of("status","receivedAt","startedAt","completedAt","sourceFileName","contentHash")) {
            values.put("scan."+field,ReflectionTestUtils.getField(registered,field));
        }
        return values;
    }
}
