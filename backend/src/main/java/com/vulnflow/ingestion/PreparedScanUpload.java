package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;

/** Validated bytes and their identity, prepared once before reserving a command upload. */
public final class PreparedScanUpload {
    private final Asset asset;
    private final String fileName;
    private final String contentHash;
    private final byte[] content;

    public PreparedScanUpload(Asset asset, String fileName, String contentHash, byte[] content) {
        this.asset = asset;
        this.fileName = fileName;
        this.contentHash = contentHash;
        this.content = content;
    }

    public Asset asset() { return asset; }
    public String fileName() { return fileName; }
    public String contentHash() { return contentHash; }
    public byte[] content() { return content; }
}
