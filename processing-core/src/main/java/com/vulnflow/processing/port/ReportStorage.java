package com.vulnflow.processing.port;

import java.util.UUID;

public interface ReportStorage {
    String store(UUID scanId, byte[] content);
    byte[] load(String payloadKey);
    void delete(String payloadKey);
    boolean exists(String payloadKey);
}
