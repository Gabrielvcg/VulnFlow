package com.vulnflow.scan;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepository extends JpaRepository<Scan, UUID> {

    Optional<Scan> findByAssetIdAndContentHash(UUID assetId, String contentHash);
}

