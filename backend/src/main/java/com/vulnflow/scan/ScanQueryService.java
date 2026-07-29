package com.vulnflow.scan;

import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanQueryService {

    private final ScanRepository scanRepository;

    public ScanQueryService(ScanRepository scanRepository) {
        this.scanRepository = scanRepository;
    }

    @Transactional(readOnly = true)
    public Page<ScanResponse> findAll(Pageable pageable) {
        return scanRepository.findAll(pageable).map(ScanResponse::from);
    }

    @Transactional(readOnly = true)
    public ScanResponse findById(UUID id) {
        Scan scan = scanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scan", id));
        return ScanResponse.from(scan);
    }
}

