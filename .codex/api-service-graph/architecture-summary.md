# API Service Graph Summary

## Inventory

- config: 15
- controller: 8
- dao: 1
- endpoint: 7
- external_client: 11
- handler: 9
- job: 13
- model: 7
- queue: 9
- repository: 24
- service: 15
- unknown: 84

## Endpoint Paths

> Manual verification: the static analyzer resolved 7 of 14 Spring endpoints.
> Direct controller-annotation inspection additionally confirmed `POST /api/v1/assets`,
> `PUT /api/v1/assets/resolve`, `GET /api/v1/assets`, `GET /api/v1/assets/{id}`,
> `GET /api/v1/findings`, `GET /api/v1/findings/{id}`, and
> `PATCH /api/v1/findings/{id}/status`. Treat this graph as a dependency aid,
> not as a complete endpoint inventory.

### GET /api/v1/dashboard/summary

- GET /api/v1/dashboard/summary -> DashboardController.summary

### GET /api/v1/ingestion-jobs

- GET /api/v1/ingestion-jobs -> IngestionJobController.findAll

### GET /api/v1/ingestion-jobs/{jobId}

- GET /api/v1/ingestion-jobs/{jobId} -> IngestionJobController.findById

### POST /api/v1/ingestion-jobs/{jobId}/redrive

- POST /api/v1/ingestion-jobs/{jobId}/redrive -> IngestionJobController.redrive

### GET /api/v1/scans

- GET /api/v1/scans -> ScanController.findAll

### POST /api/v1/scans/trivy

- POST /api/v1/scans/trivy -> ScanIngestionController.ingestTrivy

### GET /api/v1/scans/{id}

- GET /api/v1/scans/{id} -> ScanController.findById

## High Fanout Nodes

- PostgreSQLFlowIT (queue): 23 outgoing edges
- AssetResolutionResponse (queue): 23 outgoing edges
- IngestionJobProcessor (service): 10 outgoing edges
- IngestionJobController (controller): 9 outgoing edges
- DefaultScanIngestionService (service): 8 outgoing edges
- ScanController (controller): 7 outgoing edges
- ReResolvingClient (external_client): 6 outgoing edges
- FailingClient (external_client): 6 outgoing edges
- BlockingClient (external_client): 6 outgoing edges
- IngestionJobRedriveService (service): 6 outgoing edges

## Findings

- low: Added 29 low-confidence inferred dependencies based on co-located naming patterns.
- info: Detected 7 endpoints and 203 nodes.
- info: Node inventory: config=15, controller=8, dao=1, endpoint=7, external_client=11, handler=9, job=13, model=7, queue=9, repository=24, service=15, unknown=84
- low: 138 nodes are low-confidence, usually naming-based detections.
- low: 29 edges are explicit low-confidence inferred dependencies.

## Limitations

- reflection: Static analysis may be incomplete because reflection was detected. (agent/src/main/java/com/vulnflow/agent/client/VulnFlowHttpClient.java:61)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/main/java/com/vulnflow/agent/client/VulnFlowHttpClient.java:170)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/main/java/com/vulnflow/agent/config/AgentConfigLoader.java:34)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/main/java/com/vulnflow/agent/outbox/FileAgentOutbox.java:34)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/main/java/com/vulnflow/agent/scheduling/AgentStateStore.java:18)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/main/java/com/vulnflow/agent/scheduling/AssetCache.java:23)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/main/java/com/vulnflow/agent/shared/AtomicFiles.java:23)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/AgentApplicationTest.java:21)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/client/VulnFlowHttpClientTest.java:68)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/config/AgentConfigLoaderTest.java:78)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/outbox/FileAgentOutboxTest.java:39)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/scanner/TrivyImageScannerTest.java:74)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/scheduling/AgentSchedulerTest.java:67)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/scheduling/ScanCoordinatorTest.java:99)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (agent/src/test/java/com/vulnflow/agent/scheduling/UploadCoordinatorTest.java:84)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (backend/src/main/java/com/vulnflow/asset/AssetController.java:36)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (backend/src/main/java/com/vulnflow/asset/AssetService.java:37)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (backend/src/main/java/com/vulnflow/ingestion/LocalFileReportStorage.java:41)
- ambiguous_di: Static analysis may be incomplete because ambiguous di was detected. (backend/src/test/java/com/vulnflow/PostgreSQLFlowIT.java:275)
