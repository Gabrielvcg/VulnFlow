package com.vulnflow.ingestion;

import com.vulnflow.shared.exception.InvalidReportException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

@Component
public class JobFailureClassifier {

    private static final String VALIDATION_ERROR = "Report validation failed";
    private static final String MISSING_PAYLOAD_ERROR = "Stored report payload is unavailable";
    private static final String INTEGRITY_ERROR = "Stored report payload integrity verification failed";
    private static final String TRANSIENT_STORAGE_ERROR = "Temporary report storage failure";
    private static final String TRANSIENT_DATABASE_ERROR = "Temporary database failure";
    private static final String PERMANENT_ERROR = "Permanent processing failure";

    public JobFailureClassification classify(RuntimeException exception) {
        if (exception instanceof InvalidReportException) {
            return permanent(VALIDATION_ERROR);
        }
        if (exception instanceof PayloadNotFoundException) {
            return permanent(MISSING_PAYLOAD_ERROR);
        }
        if (exception instanceof PayloadIntegrityException) {
            return permanent(INTEGRITY_ERROR);
        }
        if (exception instanceof TransientReportStorageException) {
            return retryable(TRANSIENT_STORAGE_ERROR);
        }
        if (hasCause(exception, TransientDataAccessException.class)
                || hasCause(exception, RecoverableDataAccessException.class)
                || hasCause(exception, CannotGetJdbcConnectionException.class)
                || hasCause(exception, SQLTransientException.class)
                || hasCause(exception, SQLRecoverableException.class)
                || hasTransientSqlState(exception)) {
            return retryable(TRANSIENT_DATABASE_ERROR);
        }
        return permanent(PERMANENT_ERROR);
    }

    private JobFailureClassification permanent(String safeError) {
        return new JobFailureClassification(false, safeError);
    }

    private JobFailureClassification retryable(String safeError) {
        return new JobFailureClassification(true, safeError);
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasTransientSqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null
                        && (sqlState.startsWith("08")
                        || sqlState.startsWith("40")
                        || sqlState.startsWith("53")
                        || sqlState.equals("55P03")
                        || sqlState.startsWith("57P0"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
