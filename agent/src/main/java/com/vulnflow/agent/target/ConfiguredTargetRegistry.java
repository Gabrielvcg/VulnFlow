package com.vulnflow.agent.target;

import java.util.List;

public class ConfiguredTargetRegistry implements TargetRegistry {

    private final List<ScanTarget> targets;

    public ConfiguredTargetRegistry(List<ScanTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    @Override
    public List<ScanTarget> targets() {
        return targets;
    }
}
