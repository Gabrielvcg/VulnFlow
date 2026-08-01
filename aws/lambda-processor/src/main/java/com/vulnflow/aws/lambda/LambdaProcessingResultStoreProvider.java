package com.vulnflow.aws.lambda;

import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.port.ProcessingResultStore;

/** Extension point for the result-storage decision recorded in ADR-aws-result-storage. */
public interface LambdaProcessingResultStoreProvider {
    ProcessingResultStore<IngestionEventV1> create();
}
