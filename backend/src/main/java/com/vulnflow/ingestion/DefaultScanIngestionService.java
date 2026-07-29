package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetService;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScannerType;
import com.vulnflow.shared.exception.InvalidReportException;
import com.vulnflow.shared.exception.UnsupportedReportMediaTypeException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DefaultScanIngestionService implements ScanIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultScanIngestionService.class);

    private final AssetService assetService;
    private final ScanRepository scanRepository;
    private final FindingRepository findingRepository;
    private final VulnerabilityReportParser reportParser;
    private final IngestionPersistenceService persistenceService;
    private final IngestionProperties properties;

    public DefaultScanIngestionService(
            AssetService assetService,
            ScanRepository scanRepository,
            FindingRepository findingRepository,
            VulnerabilityReportParser reportParser,
            IngestionPersistenceService persistenceService,
            IngestionProperties properties) {
        this.assetService = assetService;
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.reportParser = reportParser;
        this.persistenceService = persistenceService;
        this.properties = properties;
    }

    @Override
    public ScanIngestionResponse ingestTrivy(UUID assetId, MultipartFile file) {
        Asset asset = assetService.requireAsset(assetId);
        validateFile(file);
        byte[] content = readContent(file);
        String contentHash = sha256(content);

        return scanRepository.findByAssetIdAndContentHash(assetId, contentHash)
                .map(this::duplicateResponse)
                .orElseGet(() -> processNewScan(asset, file, content, contentHash));
    }

    private ScanIngestionResponse processNewScan(
            Asset asset,
            MultipartFile file,
            byte[] content,
            String contentHash) {
        Scan scan = new Scan(asset, ScannerType.TRIVY, safeFileName(file), contentHash);
        scan.markProcessing();
        try {
            scan = scanRepository.saveAndFlush(scan);
        } catch (DataIntegrityViolationException exception) {
            return scanRepository.findByAssetIdAndContentHash(asset.getId(), contentHash)
                    .map(this::duplicateResponse)
                    .orElseThrow(() -> exception);
        }

        try {
            ParsedVulnerabilityReport report = reportParser.parse(content);
            persistenceService.complete(scan, report);
            LOGGER.info(
                    "Informe Trivy procesado: scanId={}, assetId={}, hallazgos={}",
                    scan.getId(),
                    asset.getId(),
                    report.vulnerabilities().size());
            return response(scan, report.vulnerabilities().size(), false);
        } catch (RuntimeException exception) {
            scan.markFailed();
            scanRepository.save(scan);
            LOGGER.warn(
                    "Falló el procesamiento del informe Trivy: scanId={}, assetId={}, causa={}",
                    scan.getId(),
                    asset.getId(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidReportException("The Trivy report file must not be empty");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new InvalidReportException("The Trivy report exceeds the configured file size limit");
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
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.length() <= 500 ? name : name.substring(name.length() - 500);
    }

    private ScanIngestionResponse duplicateResponse(Scan scan) {
        LOGGER.info(
                "Informe Trivy duplicado omitido: scanId={}, assetId={}",
                scan.getId(),
                scan.getAsset().getId());
        return response(scan, findingRepository.countByScanId(scan.getId()), true);
    }

    private ScanIngestionResponse response(Scan scan, long findingsImported, boolean duplicate) {
        return new ScanIngestionResponse(
                scan.getId(),
                scan.getAsset().getId(),
                scan.getStatus(),
                findingsImported,
                findingRepository.countByScanIdAndSeverity(scan.getId(), FindingSeverity.CRITICAL),
                findingRepository.countByScanIdAndSeverity(scan.getId(), FindingSeverity.HIGH),
                duplicate);
    }
}

