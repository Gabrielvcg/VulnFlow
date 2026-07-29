package com.vulnflow.asset;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
}

