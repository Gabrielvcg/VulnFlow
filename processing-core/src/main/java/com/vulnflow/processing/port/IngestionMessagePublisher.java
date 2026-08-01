package com.vulnflow.processing.port;

import com.vulnflow.contract.IngestionEventV1;

public interface IngestionMessagePublisher {
    String publish(IngestionEventV1 event);
}
