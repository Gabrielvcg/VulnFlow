package com.vulnflow.ingestion;

import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionJobQueryService {

    private final IngestionJobRepository repository;

    public IngestionJobQueryService(IngestionJobRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<IngestionJobResponse> findAll(
            IngestionJobStatus status,
            UUID scanId,
            Pageable pageable) {
        return repository.findFiltered(status, scanId, pageable).map(IngestionJobResponse::from);
    }

    @Transactional(readOnly = true)
    public IngestionJobResponse findById(UUID id) {
        return IngestionJobResponse.from(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IngestionJob", id)));
    }
}
