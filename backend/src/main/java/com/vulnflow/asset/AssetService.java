package com.vulnflow.asset;

import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {

    private final AssetRepository assetRepository;
    private final AssetIdentityRepository assetIdentityRepository;

    public AssetService(AssetRepository assetRepository, AssetIdentityRepository assetIdentityRepository) {
        this.assetRepository = assetRepository;
        this.assetIdentityRepository = assetIdentityRepository;
    }

    @Transactional
    public AssetDtos.Response create(AssetDtos.CreateRequest request) {
        Asset asset = new Asset(
                request.name().trim(),
                request.type(),
                normalizeOptional(request.externalReference()));
        try {
            return AssetDtos.Response.from(assetRepository.saveAndFlush(asset));
        } catch (DataIntegrityViolationException exception) {
            throw new AssetIdentityConflictException(
                    "An asset with the same type and external reference already exists", exception);
        }
    }

    @Transactional
    public AssetDtos.Resolution resolve(AssetDtos.ResolveRequest request) {
        String externalReference = request.externalReference().trim();
        UUID candidateId = UUID.randomUUID();
        boolean created = assetIdentityRepository.insertIfAbsent(
                candidateId,
                request.name().trim(),
                request.type(),
                externalReference);
        Asset asset = assetRepository.findByTypeAndExternalReference(request.type(), externalReference)
                .orElseThrow(() -> new IllegalStateException("Resolved asset was not found"));
        return new AssetDtos.Resolution(AssetDtos.Response.from(asset), created);
    }

    @Transactional(readOnly = true)
    public Page<AssetDtos.Response> findAll(Pageable pageable) {
        return assetRepository.findAll(pageable).map(AssetDtos.Response::from);
    }

    @Transactional(readOnly = true)
    public AssetDtos.Response findById(UUID id) {
        return AssetDtos.Response.from(requireAsset(id));
    }

    @Transactional(readOnly = true)
    public Asset requireAsset(UUID id) {
        return assetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", id));
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
