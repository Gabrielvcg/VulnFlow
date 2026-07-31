package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnflow.shared.exception.InvalidReportException;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

class JobFailureClassifierTest {

    private final JobFailureClassifier classifier = new JobFailureClassifier();

    @Test
    void classifiesKnownPermanentFailuresWithoutRetry() {
        assertPermanent(new InvalidReportException("invalid"), "Report validation failed");
        assertPermanent(new PayloadNotFoundException("missing"), "Stored report payload is unavailable");
        assertPermanent(
                new PayloadIntegrityException("integrity"),
                "Stored report payload integrity verification failed");
        assertPermanent(new DataIntegrityViolationException("constraint"), "Permanent processing failure");
    }

    @Test
    void classifiesExplicitStorageAndDatabaseFailuresAsRetryable() {
        assertRetryable(
                new TransientReportStorageException("storage", new IOException("temporary")),
                "Temporary report storage failure");
        assertRetryable(
                new TransientDataAccessResourceException("database"),
                "Temporary database failure");
        assertRetryable(
                new RuntimeException(new SQLTransientConnectionException("database")),
                "Temporary database failure");
        assertRetryable(
                new RuntimeException(new SQLException("connection lost", "08006")),
                "Temporary database failure");
    }

    @Test
    void treatsUnknownRuntimeFailuresConservativelyAsPermanent() {
        assertPermanent(
                new ReportStorageException("permanent storage", new IOException("configuration")),
                "Permanent processing failure");
        assertPermanent(new IllegalStateException("bug"), "Permanent processing failure");
        assertPermanent(new RuntimeException("unknown"), "Permanent processing failure");
    }

    private void assertPermanent(RuntimeException exception, String safeError) {
        JobFailureClassification classification = classifier.classify(exception);
        assertThat(classification.retryable()).isFalse();
        assertThat(classification.safeError()).isEqualTo(safeError);
    }

    private void assertRetryable(RuntimeException exception, String safeError) {
        JobFailureClassification classification = classifier.classify(exception);
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.safeError()).isEqualTo(safeError);
    }
}
