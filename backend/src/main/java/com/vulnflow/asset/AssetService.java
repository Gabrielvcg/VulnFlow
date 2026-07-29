package com.vulnflow.asset;

import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {

    private final AssetRepository assetRepository;

    public AssetService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @Transactional
    public AssetDtos.Response create(AssetDtos.CreateRequest request) {
        Asset asset = new Asset(
                request.name().trim(),
                request.type(),
                normalizeOptional(request.externalReference()));
        return AssetDtos.Response.from(assetRepository.save(asset));
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

