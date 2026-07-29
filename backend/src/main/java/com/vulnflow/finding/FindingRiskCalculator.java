package com.vulnflow.finding;

public interface FindingRiskCalculator {

    int calculate(FindingSeverity severity, boolean knownExploited);
}

