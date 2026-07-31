package com.vulnflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.ingestion.IngestionProperties;
import com.vulnflow.processing.DefaultFindingRiskCalculator;
import com.vulnflow.processing.PayloadIntegrityVerifier;
import com.vulnflow.processing.TrivyVulnerabilityReportParser;
import com.vulnflow.processing.VulnerabilityReportParser;
import com.vulnflow.processing.VulnerabilityReportProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessingConfiguration {
    @Bean
    VulnerabilityReportParser vulnerabilityReportParser(
            ObjectMapper objectMapper, IngestionProperties properties) {
        return new TrivyVulnerabilityReportParser(objectMapper, properties.maxDescriptionLength());
    }

    @Bean
    VulnerabilityReportProcessor vulnerabilityReportProcessor(VulnerabilityReportParser parser) {
        return new VulnerabilityReportProcessor(
                new PayloadIntegrityVerifier(), parser, new DefaultFindingRiskCalculator());
    }
}
