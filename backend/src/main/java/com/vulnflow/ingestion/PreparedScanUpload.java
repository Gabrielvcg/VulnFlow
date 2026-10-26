package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;

/** Validated bytes and their identity, prepared once before reserving a command upload. */
public record PreparedScanUpload(Asset asset, String fileName, String contentHash, byte[] content) {}
