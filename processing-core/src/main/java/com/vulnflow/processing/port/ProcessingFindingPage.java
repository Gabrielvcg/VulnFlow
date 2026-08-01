package com.vulnflow.processing.port;

import java.util.List;

public record ProcessingFindingPage(List<ProcessingFindingResult> findings, String nextCursor) {
    public ProcessingFindingPage {
        findings = List.copyOf(findings);
    }
}
