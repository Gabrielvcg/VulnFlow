package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.vulnflow.asset.*;
import com.vulnflow.aws.ingestion.*;
import com.vulnflow.processing.port.ReportStorage;
import com.vulnflow.scan.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisteredUploadLookupTest {
    @ParameterizedTest
    @ValueSource(strings = {"missing", "legacy", "queued", "processing", "completed", "failed"})
    void localLookupIsReadOnlyAndPreservesExistingOutcomePolicy(String state) {
        Asset asset = new Asset("demo", AssetType.HOST, "demo-host");
        Scan scan = new Scan(asset, ScannerType.TRIVY, "original.json", "a".repeat(64));
        ScanRepository scans = mock(ScanRepository.class);
        IngestionJobRepository jobs = mock(IngestionJobRepository.class);
        ReportStorage storage = mock(ReportStorage.class);
        IngestionJob job = new IngestionJob(scan, "original-key", 3);
        if (state.equals("processing")) { scan.markProcessing(); job.claim(Instant.now()); }
        if (state.equals("completed")) scan.markCompleted("1");
        if (state.equals("failed")) { scan.markFailed("safe"); job.claim(Instant.now()); job.markDeadLetter(Instant.now(),"safe"); }
        when(scans.findByAssetIdAndContentHash(asset.getId(),scan.getContentHash()))
                .thenReturn(state.equals("missing") ? Optional.empty() : Optional.of(scan));
        when(jobs.findByScanId(scan.getId())).thenReturn(state.equals("legacy") ? Optional.empty() : Optional.of(job));
        ScanRegistrationService service = new ScanRegistrationService(scans,jobs,storage,null);
        Optional<IngestionSubmission> result = service.findRegistered(asset.getId(),scan.getContentHash());
        if (state.equals("missing") || state.equals("legacy")) assertThat(result).isEmpty();
        else {
            IngestionSubmission value = result.orElseThrow();
            assertThat(value.scanId()).isEqualTo(scan.getId());
            assertThat(value.jobId()).isEqualTo(job.getId());
            assertThat(value.outcome().name()).isEqualTo(state.equals("completed") ? "DUPLICATE"
                    : state.equals("failed") ? "DEAD_LETTER" : state.equals("processing") ? "ALREADY_PROCESSING" : "ALREADY_QUEUED");
        }
        verify(scans).findByAssetIdAndContentHash(asset.getId(),scan.getContentHash());
        if (!state.equals("missing")) verify(jobs).findByScanId(scan.getId());
        verifyNoMoreInteractions(scans,jobs);
        verifyNoInteractions(storage);
        assertThat(scan.getSourceFileName()).isEqualTo("original.json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "legacy", "completed-legacy", "queued", "publishing", "published", "failed"})
    void awsLookupReadsExistingOutboxWithoutPayloadOrRegistrationEffects(String state) {
        Asset asset = new Asset("demo", AssetType.HOST, "demo-host");
        Scan scan = new Scan(asset, ScannerType.TRIVY, "original.json", "b".repeat(64));
        if (state.equals("completed-legacy")) scan.markCompleted("1");
        ScanRepository scans = mock(ScanRepository.class);
        AwsPublicationOutboxRepository outboxes = mock(AwsPublicationOutboxRepository.class);
        ReportStorage storage = mock(ReportStorage.class);
        AwsPublicationOutbox outbox = new AwsPublicationOutbox(UUID.randomUUID(),scan,"original-key","{}",3,Instant.now());
        if (List.of("publishing","published","failed").contains(state)) outbox.claim(Instant.now());
        if (state.equals("published")) outbox.markPublished(Instant.now());
        if (state.equals("failed")) outbox.markPublicationFailure(Instant.now(),java.time.Duration.ofSeconds(1),"safe",true);
        when(scans.findByAssetIdAndContentHash(asset.getId(),scan.getContentHash()))
                .thenReturn(state.equals("missing") ? Optional.empty() : Optional.of(scan));
        when(outboxes.findByScanId(scan.getId())).thenReturn(state.contains("legacy") ? Optional.empty() : Optional.of(outbox));
        AwsScanSubmissionService service = new AwsScanSubmissionService(storage,
                new AwsScanRegistrationTransaction(scans,outboxes,null));
        Optional<IngestionSubmission> result = service.findRegistered(asset.getId(),scan.getContentHash());
        if (state.equals("missing") || state.equals("legacy")) assertThat(result).isEmpty();
        else {
            IngestionSubmission value = result.orElseThrow();
            assertThat(value.scanId()).isEqualTo(scan.getId());
            assertThat(value.jobId()).isNull();
            assertThat(value.eventId()).isEqualTo(state.equals("completed-legacy") ? null : outbox.getEventId());
            assertThat(value.publicationStatus()).isEqualTo(state.equals("completed-legacy") ? null : outbox.getStatus().name());
            assertThat(value.outcome().name()).isEqualTo(state.equals("completed-legacy") ? "DUPLICATE"
                    : state.equals("failed") ? "DEAD_LETTER" : state.equals("publishing") ? "ALREADY_PROCESSING" : "ALREADY_QUEUED");
        }
        verify(scans).findByAssetIdAndContentHash(asset.getId(),scan.getContentHash());
        if (!state.equals("missing")) verify(outboxes).findByScanId(scan.getId());
        verifyNoMoreInteractions(scans,outboxes);
        verifyNoInteractions(storage);
    }
}
