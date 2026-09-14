package com.vulnflow.ui.scan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UiAdmissionLock {
    private final JdbcTemplate jdbc;

    public UiAdmissionLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes quota checks and insertion across all backend instances. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.query("SELECT pg_advisory_xact_lock(1447447628, 1)",
                (ResultSetExtractor<Void>) result -> null);
    }
}
