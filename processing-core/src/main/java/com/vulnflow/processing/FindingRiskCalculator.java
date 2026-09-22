package com.vulnflow.processing;

public interface FindingRiskCalculator {
    int calculate(FindingSeverity severity, boolean knownExploited);

    default int calculate(FindingSeverity severity, boolean knownExploited, Double cvssScore) {
        return calculate(severity, knownExploited);
    }
}
