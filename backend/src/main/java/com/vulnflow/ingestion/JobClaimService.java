package com.vulnflow.ingestion;

import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobClaimService {

    private final IngestionJobRepository jobRepository;
    private final ScanRepository scanRepository;

    public JobClaimService(
            IngestionJobRepository jobRepository,
            ScanRepository scanRepository) {
        this.jobRepository = jobRepository;
        this.scanRepository = scanRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<JobClaim> claimAvailable(int batchSize) {
        Instant now = Instant.now();
        List<UUID> ids = jobRepository.findClaimableIds(now, batchSize);
        List<JobClaim> claims = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            IngestionJob job = jobRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("A claimed ingestion job disappeared"));
            Scan scan = scanRepository.findByIdForUpdate(job.getScan().getId())
                    .orElseThrow(() -> new IllegalStateException("The scan for a claimed job disappeared"));
            UUID claimToken = job.claim(now);
            scan.markProcessing();
            claims.add(new JobClaim(
                    job.getId(),
                    scan.getId(),
                    scan.getAsset().getId(),
                    job.getPayloadKey(),
                    scan.getContentHash(),
                    claimToken,
                    job.getAttemptCount(),
                    job.getMaxAttempts()));
        }
        return claims;
    }
}
