package com.vulnflow.ingestion;

import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ScanIngestionService {

    ScanIngestionResponse ingestTrivy(UUID assetId, MultipartFile file);
}

