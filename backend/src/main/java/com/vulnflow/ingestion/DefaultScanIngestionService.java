package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetService;
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

    private final AssetService assetService;
    private final ScanSubmissionService registrationService;
    private final IngestionProperties properties;
    private final IngestionMetrics metrics;

    public DefaultScanIngestionService(
            AssetService assetService,
            ScanSubmissionService registrationService,
            IngestionProperties properties,
            IngestionMetrics metrics) {
        this.assetService = assetService;
        this.registrationService = registrationService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public ScanIngestionResponse ingestTrivy(UUID assetId, MultipartFile file) {
        Asset asset = assetService.requireAsset(assetId);
        validateFile(file);
        byte[] content = readContent(file);
        String contentHash = sha256(content);

        IngestionSubmission submission = registrationService.registerReceived(
                asset,
                safeFileName(file),
                contentHash,
                content);
        if (submission.outcome() == ScanIngestionOutcome.ACCEPTED) {
            metrics.jobAccepted();
        }

        LOGGER.info(
                "Informe Trivy recibido: jobId={}, scanId={}, assetId={}, resultado={}",
                submission.jobId(),
                submission.scanId(),
                submission.assetId(),
                submission.outcome());
        return new ScanIngestionResponse(
                submission.scanId(),
                submission.jobId(),
                submission.assetId(),
                submission.scanStatus(),
                submission.jobStatus(),
                submission.outcome(),
                submission.eventId(),
                submission.publicationStatus());
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
}
