export type DemoStage = {
  state: string;
  label: string;
  eventId: string;
  timestamp: string;
  latency: string;
  detail: string;
};

export const recordedScan = {
  fixtureId: 'debian-demo-2026-08',
  target: 'debian:12-slim',
  reportFile: 'trivy-report.json',
  reportSize: '42.7 KB',
  reportHash: 'sha256:9d7c…a41e',
  scanner: 'Trivy 0.58',
  duration: '41.8 s',
  requestId: 'req_demo_01HZX7K4',
  scanId: 'scan_demo_01HZX7K4',
  eventId: 'evt_demo_01HZX7K4',
  correlationId: 'corr_demo_01HZX7K4',
  summary: { CRITICAL: 7, HIGH: 42, MEDIUM: 93, LOW: 48 },
  stages: [
    { state: 'REQUESTED', label: 'Policy accepted', eventId: 'evt_demo_01', timestamp: '10:14:02.000', latency: '0 ms', detail: 'The target matched the approved catalog. No free-form reference was accepted.' },
    { state: 'CLAIMED', label: 'Fenced lease', eventId: 'evt_demo_02', timestamp: '10:14:04.214', latency: '2.2 s', detail: 'The Agent claimed the request with a lease and fencing token.' },
    { state: 'RUNNING', label: 'Trivy execution', eventId: 'evt_demo_03', timestamp: '10:14:05.006', latency: '792 ms', detail: 'The Agent executed the bounded scanner against the approved image.' },
    { state: 'UPLOADING', label: 'Report upload to API', eventId: 'evt_demo_04', timestamp: '10:14:33.801', latency: '28.8 s', detail: 'The Agent uploaded the Trivy JSON to the API, which stored it in the versioned S3 reports bucket.' },
    { state: 'PROCESSING', label: 'SQS and Lambda processing', eventId: 'evt_demo_05', timestamp: '10:14:36.122', latency: '2.3 s', detail: 'The PostgreSQL outbox publisher sent the event to SQS and Lambda parsed it idempotently.' },
    { state: 'COMPLETED', label: 'Findings stored in DynamoDB', eventId: 'evt_demo_06', timestamp: '10:14:44.918', latency: '8.8 s', detail: 'The summary and 190 findings became readable from DynamoDB through the API.' },
  ] satisfies DemoStage[],
  sampleFindings: [
    { id: 'CVE-DEMO-0001', packageName: 'openssl', severity: 'CRITICAL', fixed: '3.0.14-1~deb12u1' },
    { id: 'CVE-DEMO-0002', packageName: 'libcurl4', severity: 'HIGH', fixed: '7.88.1-10+deb12u6' },
    { id: 'CVE-DEMO-0003', packageName: 'zlib1g', severity: 'MEDIUM', fixed: '1:1.2.13.dfsg-1' },
  ],
} as const;
