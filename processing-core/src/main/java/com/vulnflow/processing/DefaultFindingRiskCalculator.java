package com.vulnflow.processing;

public final class DefaultFindingRiskCalculator implements FindingRiskCalculator {
    @Override
    public int calculate(FindingSeverity severity, boolean knownExploited, Double cvssScore) {
        int base = cvssScore != null && Double.isFinite(cvssScore) && cvssScore >= 0 && cvssScore <= 10
                ? (int) Math.round(cvssScore * 10) : calculate(severity, false);
        return Math.min(100, base + (knownExploited ? 10 : 0));
    }
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
