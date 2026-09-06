package com.vulnflow.jobs;

import com.vulnflow.ui.scan.UiScanRequestRepository;
import com.vulnflow.ui.scan.UiScanRequestService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances UI requests even when no browser is polling their detail endpoint. */
@Component
@ConditionalOnProperty(name = "vulnflow.ui.enabled", havingValue = "true")
public class UiScanReconciliationJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(UiScanReconciliationJob.class);
    private final UiScanRequestRepository requests;
    private final UiScanRequestService service;
    private int page;

    public UiScanReconciliationJob(UiScanRequestRepository requests, UiScanRequestService service) {
        this.requests = requests;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${vulnflow.ui.reconciliation-interval:10s}")
    public void reconcile() {
        Page<UUID> pending = requests.findProcessingIds(PageRequest.of(page, 25));
        page = pending.hasNext() ? page + 1 : 0;
        if (pending.isEmpty()) return;
        LOGGER.debug("Se inicia la sincronización de solicitudes: cantidad={}", pending.getNumberOfElements());
        int failed = 0;
        for (UUID id : pending) {
            try {
                service.reconcile(id);
            } catch (RuntimeException exception) {
                failed++;
                LOGGER.warn("No se pudo sincronizar la solicitud: requestId={}, causa={}",
                        id, exception.getClass().getSimpleName());
            }
        }
        LOGGER.debug("Finalizó la sincronización de solicitudes: revisadas={}, errores={}",
                pending.getNumberOfElements(), failed);
    }
}
