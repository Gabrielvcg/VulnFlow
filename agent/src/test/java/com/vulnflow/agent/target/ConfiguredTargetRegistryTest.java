package com.vulnflow.agent.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfiguredTargetRegistryTest {

    @Test
    void matchesOnlyTheConfiguredTargetIdentity() {
        ScanTarget configured = new ScanTarget("alpine-demo", TargetType.CONTAINER_IMAGE, "alpine:3.15");
        ConfiguredTargetRegistry registry = new ConfiguredTargetRegistry(List.of(configured));

        assertThat(registry.contains(configured)).isTrue();
        assertThat(registry.contains(new ScanTarget("other-name", TargetType.CONTAINER_IMAGE, "alpine:3.15"))).isTrue();
        assertThat(registry.contains(new ScanTarget("alpine-demo", TargetType.CONTAINER_IMAGE, "debian:12"))).isFalse();
    }
}
