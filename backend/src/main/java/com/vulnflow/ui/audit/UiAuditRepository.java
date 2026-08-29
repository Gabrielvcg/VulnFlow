package com.vulnflow.ui.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiAuditRepository extends JpaRepository<UiAuditEvent, UUID> {
    Page<UiAuditEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
