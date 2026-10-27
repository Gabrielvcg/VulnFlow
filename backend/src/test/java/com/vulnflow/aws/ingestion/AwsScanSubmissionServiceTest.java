package com.vulnflow.aws.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetType;
import com.vulnflow.ingestion.IngestionSubmission;
import com.vulnflow.ingestion.ScanIngestionOutcome;
import com.vulnflow.processing.port.ReportStorage;
import com.vulnflow.scan.ScanStatus;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;

class AwsScanSubmissionServiceTest {
    private final ReportStorage storage = mock(ReportStorage.class);
    private final AwsScanRegistrationTransaction registration = mock(AwsScanRegistrationTransaction.class);
    private final AwsScanSubmissionService service = new AwsScanSubmissionService(storage, registration);
    private final Asset asset = new Asset("demo", AssetType.HOST, "demo-host");
    private final byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);

    @Test
    void retainsReferencedPayloadWhenRegistrationOutcomeIsUnknownAndPreservesOriginalException() {
        String payloadKey = "candidate/report.json";
        Set<String> storedPayloads = new HashSet<>();
        Set<String> committedReferences = new HashSet<>();
        TransactionSystemException failure = new TransactionSystemException("Commit acknowledgement unavailable");
        when(storage.store(any(UUID.class), eq(content))).thenAnswer(invocation -> {
            storedPayloads.add(payloadKey);
            return payloadKey;
        });
        doAnswer(invocation -> {
            storedPayloads.remove(invocation.getArgument(0));
            return null;
        }).when(storage).delete(any());
        // Model a committed reference followed by a lost acknowledgement, not a real database experiment.
        when(registration.register(any(UUID.class), eq(asset.getId()), eq("report.json"), eq("hash"), eq(payloadKey)))
                .thenAnswer(invocation -> {
                    committedReferences.add(payloadKey);
                    throw failure;
                });

        assertThatThrownBy(() -> service.registerReceived(asset, "report.json", "hash", content))
                .isSameAs(failure);

        assertThat(committedReferences).containsExactly(payloadKey);
        assertThat(storedPayloads).containsExactly(payloadKey);
        verify(storage, never()).delete(any());
    }

    @Test
    void retainsPayloadWhenRegistrationFailsWithoutACommittedReference() {
        String payloadKey = "unreferenced/report.json";
        RuntimeException failure = new IllegalStateException("Registration unavailable");
        when(storage.store(any(UUID.class), eq(content))).thenReturn(payloadKey);
        when(registration.register(any(UUID.class), eq(asset.getId()), eq("report.json"), eq("hash"), eq(payloadKey)))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.registerReceived(asset, "report.json", "hash", content))
                .isSameAs(failure);

        verify(storage, never()).delete(any());
    }

    @Test
    void retainsPayloadOwnedBySuccessfulRegistration() {
        String payloadKey = "accepted/report.json";
        IngestionSubmission submission = submission(ScanIngestionOutcome.ACCEPTED);
        when(storage.store(any(UUID.class), eq(content))).thenReturn(payloadKey);
        when(registration.register(any(UUID.class), eq(asset.getId()), eq("report.json"), eq("hash"), eq(payloadKey)))
                .thenReturn(new AwsRegistrationResult(submission, true));

        assertThat(service.registerReceived(asset, "report.json", "hash", content)).isSameAs(submission);

        verify(storage, never()).delete(any());
    }

    @Test
    void deletesOnlyTheCandidateUploadWhenSuccessfulRegistrationDoesNotOwnIt() {
        String payloadKey = "duplicate-candidate/report.json";
        IngestionSubmission submission = submission(ScanIngestionOutcome.ALREADY_QUEUED);
        when(storage.store(any(UUID.class), eq(content))).thenReturn(payloadKey);
        when(registration.register(any(UUID.class), eq(asset.getId()), eq("report.json"), eq("hash"), eq(payloadKey)))
                .thenReturn(new AwsRegistrationResult(submission, false));

        assertThat(service.registerReceived(asset, "report.json", "hash", content)).isSameAs(submission);

        verify(storage).delete(payloadKey);
    }

    @Test
    void preservesKnownDuplicateResultWhenCandidateCleanupFails() {
        String payloadKey = "duplicate-candidate/report.json";
        IngestionSubmission submission = submission(ScanIngestionOutcome.ALREADY_QUEUED);
        when(storage.store(any(UUID.class), eq(content))).thenReturn(payloadKey);
        when(registration.register(any(UUID.class), eq(asset.getId()), eq("report.json"), eq("hash"), eq(payloadKey)))
                .thenReturn(new AwsRegistrationResult(submission, false));
        doThrow(new IllegalStateException("Storage unavailable")).when(storage).delete(payloadKey);

        assertThat(service.registerReceived(asset, "report.json", "hash", content)).isSameAs(submission);

        verify(storage).delete(payloadKey);
    }

    private IngestionSubmission submission(ScanIngestionOutcome outcome) {
        return new IngestionSubmission(
                UUID.randomUUID(), null, asset.getId(), ScanStatus.RECEIVED, null, outcome,
                UUID.randomUUID(), "PUBLISH_PENDING");
    }
}
