package com.vulnflow.finding;

import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FindingService {

    private final FindingRepository findingRepository;

    public FindingService(FindingRepository findingRepository) {
        this.findingRepository = findingRepository;
    }

    @Transactional(readOnly = true)
    public Page<FindingDtos.Response> findAll(
            FindingSeverity severity,
            FindingStatus status,
            UUID assetId,
            Boolean knownExploited,
            Pageable pageable) {
        return findingRepository.findFiltered(severity, status, assetId, knownExploited, pageable)
                .map(FindingDtos.Response::from);
    }

    @Transactional(readOnly = true)
    public FindingDtos.Response findById(UUID id) {
        return FindingDtos.Response.from(requireFinding(id));
    }

    @Transactional
    public FindingDtos.Response updateStatus(UUID id, FindingDtos.StatusUpdateRequest request) {
        Finding finding = requireFinding(id);
        finding.updateStatus(request.status());
        return FindingDtos.Response.from(findingRepository.save(finding));
    }

    private Finding requireFinding(UUID id) {
        return findingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Finding", id));
    }
}

