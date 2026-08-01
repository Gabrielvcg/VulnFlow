package com.vulnflow.processing;

public final class DefaultFindingRiskCalculator implements FindingRiskCalculator {
    @Override
    public int calculate(FindingSeverity severity, boolean knownExploited) {
        int baseScore = switch (severity) {
            case UNKNOWN -> 0;
            case LOW -> 20;
            case MEDIUM -> 40;
            case HIGH -> 70;
            case CRITICAL -> 90;
        };
        return Math.min(100, baseScore + (knownExploited ? 10 : 0));
    }
}
