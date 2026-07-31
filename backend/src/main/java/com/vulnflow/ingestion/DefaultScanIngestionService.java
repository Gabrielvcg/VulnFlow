package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetService;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.InvalidReportException;
import com.vulnflow.shared.exception.ReportTooLargeException;
import com.vulnflow.shared.exception.UnsupportedReportMediaTypeException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DefaultScanIngestionService implements ScanIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultScanIngestionService.class);
    private static final String PROCESSING_FAILURE_REASON = "Report processing failed";

    private final AssetService assetService;
    private final FindingRepository findingRepository;
    private final VulnerabilityReportParser reportParser;
    private final ScanRegistrationService registrationService;
    private final IngestionPersistenceService persistenceService;
    private final ScanFailureService failureService;
    private final IngestionProperties properties;

    public DefaultScanIngestionService(
            AssetService assetService,
            FindingRepository findingRepository,
            VulnerabilityReportParser reportParser,
            ScanRegistrationService registrationService,
            IngestionPersistenceService persistenceService,
            ScanFailureService failureService,
            IngestionProperties properties) {
        this.assetService = assetService;
        this.findingRepository = findingRepository;
        this.reportParser = reportParser;
        this.registrationService = registrationService;
        this.persistenceService = persistenceService;
        this.failureService = failureService;
        this.properties = properties;
    }

    @Override
    public ScanIngestionResponse ingestTrivy(UUID assetId, MultipartFile file) {
        Asset asset = assetService.requireAsset(assetId);
        validateFile(file);
        byte[] content = readContent(file);
        String contentHash = sha256(content);

        ScanRegistration registration = registrationService.registerProcessing(
                asset,
                safeFileName(file),
                contentHash);
        if (registration.outcome() == ScanIngestionOutcome.DUPLICATE) {
            return duplicateResponse(registration);
        }
        if (registration.outcome() == ScanIngestionOutcome.ALREADY_PROCESSING) {
            return response(registration, 0, true);
        }

        return processClaimedScan(registration, content);
    }

    private ScanIngestionResponse processClaimedScan(
            ScanRegistration registration,
            byte[] content) {
        ParsedVulnerabilityReport report;
        try {
            report = reportParser.parse(content);
            persistenceService.complete(registration.scanId(), report);
        } catch (RuntimeException exception) {
            try {
                failureService.markFailed(registration.scanId(), PROCESSING_FAILURE_REASON);
            } catch (RuntimeException markFailureException) {
                exception.addSuppressed(markFailureException);
                LOGGER.error(
                        "No se pudo registrar el fallo del scan: scanId={}, causa={}",
                        registration.scanId(),
                        markFailureException.getClass().getSimpleName());
            }
            LOGGER.warn(
                    "Falló el procesamiento del informe Trivy: scanId={}, assetId={}, causa={}",
                    registration.scanId(),
                    registration.assetId(),
                    exception.getClass().getSimpleName());
            throw exception;
        }

        LOGGER.info(
                "Informe Trivy procesado: scanId={}, assetId={}, hallazgos={}, resultado={}",
                registration.scanId(),
                registration.assetId(),
                report.vulnerabilities().size(),
                registration.outcome());
        return response(
                new ScanRegistration(
                        registration.scanId(),
                        registration.assetId(),
                        ScanStatus.COMPLETED,
                        registration.outcome()),
                report.vulnerabilities().size(),
                false);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidReportException("The Trivy report file must not be empty");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new ReportTooLargeException("The Trivy report exceeds the configured file size limit");
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !(contentType.equalsIgnoreCase("application/json")
                || contentType.toLowerCase().endsWith("+json"))) {
            throw new UnsupportedReportMediaTypeException("Only JSON report files are accepted");
        }
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new InvalidReportException("The uploaded report could not be read", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "trivy-report.json";
        }
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("\\p{Cntrl}", "_");
        if (name.isBlank()) {
            return "trivy-report.json";
        }
        return name.length() <= 500 ? name : name.substring(name.length() - 500);
    }

    private ScanIngestionResponse duplicateResponse(ScanRegistration registration) {
        LOGGER.info(
                "Informe Trivy duplicado omitido: scanId={}, assetId={}",
                registration.scanId(),
                registration.assetId());
        return response(registration, 0, true);
    }

    private ScanIngestionResponse response(
            ScanRegistration registration,
            long findingsImported,
            boolean duplicate) {
        long totalFindings = findingRepository.countByScanId(registration.scanId());
        return new ScanIngestionResponse(
                registration.scanId(),
                registration.assetId(),
                registration.status(),
                registration.outcome(),
                findingsImported,
                totalFindings,
                findingRepository.countByScanIdAndSeverity(
                        registration.scanId(),
                        FindingSeverity.CRITICAL),
                findingRepository.countByScanIdAndSeverity(
                        registration.scanId(),
                        FindingSeverity.HIGH),
                duplicate);
    }
}
