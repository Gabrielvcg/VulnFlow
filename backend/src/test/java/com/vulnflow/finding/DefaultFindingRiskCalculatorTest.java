package com.vulnflow.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DefaultFindingRiskCalculatorTest {

    private final DefaultFindingRiskCalculator calculator = new DefaultFindingRiskCalculator();

    @ParameterizedTest
    @CsvSource({
        "UNKNOWN, 0",
        "LOW, 20",
        "MEDIUM, 40",
        "HIGH, 70",
        "CRITICAL, 90"
    })
    void calculatesBaseScore(FindingSeverity severity, int expected) {
        assertThat(calculator.calculate(severity, false)).isEqualTo(expected);
    }

    @Test
    void addsKnownExploitedBonusAndCapsAtOneHundred() {
        assertThat(calculator.calculate(FindingSeverity.HIGH, true)).isEqualTo(80);
        assertThat(calculator.calculate(FindingSeverity.CRITICAL, true)).isEqualTo(100);
    }
}

