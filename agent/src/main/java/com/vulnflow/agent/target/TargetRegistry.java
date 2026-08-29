package com.vulnflow.agent.target;

import java.util.List;

public interface TargetRegistry {

    List<ScanTarget> targets();

    default boolean contains(ScanTarget target) {
        return target != null && targets().stream().anyMatch(configured -> configured.stableKey().equals(target.stableKey()));
    }
}
