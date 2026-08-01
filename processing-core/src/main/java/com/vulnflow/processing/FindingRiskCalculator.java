package com.vulnflow.processing;

public interface FindingRiskCalculator {
    int calculate(FindingSeverity severity, boolean knownExploited);
}
