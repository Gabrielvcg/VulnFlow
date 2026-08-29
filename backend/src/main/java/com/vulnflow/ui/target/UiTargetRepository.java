package com.vulnflow.ui.target;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiTargetRepository extends JpaRepository<UiTarget, UUID> {
    List<UiTarget> findAllByOrderByNameAsc();
    List<UiTarget> findByEnabledTrueOrderByNameAsc();
    Optional<UiTarget> findByTypeAndExternalReference(com.vulnflow.asset.AssetType type, String externalReference);
}
