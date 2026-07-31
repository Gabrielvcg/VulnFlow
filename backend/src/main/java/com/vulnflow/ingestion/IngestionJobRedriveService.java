package com.vulnflow.ingestion;

import com.vulnflow.processing.port.ReportStorage;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionJobRedriveService {

    private final IngestionJobRepository jobRepository;
    private final ScanRepository scanRepository;
    private final ReportStorage reportStorage;

    public IngestionJobRedriveService(
            IngestionJobRepository jobRepository,
            ScanRepository scanRepository,
            ReportStorage reportStorage) {
        this.jobRepository = jobRepository;
        this.scanRepository = scanRepository;
        this.reportStorage = reportStorage;
    }

    @Transactional
    public IngestionJobResponse redrive(UUID jobId) {
        IngestionJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("IngestionJob", jobId));
        if (job.getStatus() != IngestionJobStatus.DEAD_LETTER) {
            throw new JobStateConflictException("Only a dead-letter ingestion job can be redriven");
        }
        if (!reportStorage.exists(job.getPayloadKey())) {
            throw new JobStateConflictException("The stored report payload is unavailable");
        }
        Scan scan = scanRepository.findByIdForUpdate(job.getScan().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scan", job.getScan().getId()));
        job.redrive(Instant.now());
        scan.markReceived();
        return IngestionJobResponse.from(job);
    }
}
