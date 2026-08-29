package com.vulnflow.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.agent.target.ScanTarget;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class VulnFlowHttpClient implements VulnFlowClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final URI apiUrl;
    private final String apiKey;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VulnFlowHttpClient(
            URI apiUrl,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout,
            ObjectMapper objectMapper) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public AssetResolution resolveAsset(ScanTarget target) {
        byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(Map.of(
                    "name", target.name(),
                    "type", target.type().name(),
                    "externalReference", target.reference()));
        } catch (IOException exception) {
            throw new IllegalStateException("Asset resolution request could not be serialized", exception);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint("api/v1/assets/resolve"))
                .timeout(requestTimeout)
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        HttpResult response = send(request);
        if (response.status() == 200 || response.status() == 201) {
            try {
                JsonNode body = objectMapper.readTree(response.body());
                return new AssetResolution(UUID.fromString(body.path("id").asText()), response.status() == 201);
            } catch (RuntimeException | IOException exception) {
                throw new VulnFlowClientException(
                        ClientFailureKind.RETRYABLE,
                        "VulnFlow returned an invalid asset response",
                        exception);
            }
        }
        throw classify(response.status(), false);
    }

    @Override
    public UploadReceipt uploadTrivyReport(UUID assetId, Path report) {
        return uploadTrivyReport(assetId, report, null, null);
    }

    @Override
    public UploadReceipt uploadTrivyReport(UUID assetId, Path report, UUID scanRequestId, UUID claimToken) {
        String boundary = "vulnflow-agent-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"report.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(prefix),
                    HttpRequest.BodyPublishers.ofFile(report),
                    HttpRequest.BodyPublishers.ofByteArray(suffix));
        } catch (IOException exception) {
            throw new VulnFlowClientException(
                    ClientFailureKind.PERMANENT,
                    "Stored outbox report is unavailable",
                    exception);
        }
        String encodedAsset = URLEncoder.encode(assetId.toString(), StandardCharsets.UTF_8);
        String query = "api/v1/scans/trivy?assetId=" + encodedAsset;
        if (scanRequestId != null && claimToken != null) {
            query += "&scanRequestId=" + URLEncoder.encode(scanRequestId.toString(), StandardCharsets.UTF_8)
                    + "&claimToken=" + URLEncoder.encode(claimToken.toString(), StandardCharsets.UTF_8);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint(query))
                .timeout(requestTimeout)
                .header("X-API-Key", apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();
        HttpResult response = send(request);
        if (response.status() == 200 || response.status() == 202) {
            try {
                JsonNode json = objectMapper.readTree(response.body());
                return new UploadReceipt(
                        uuidOrNull(json.path("scanId").asText(null)),
                        uuidOrNull(json.path("jobId").asText(null)),
                        json.path("outcome").asText("UNKNOWN"));
            } catch (RuntimeException | IOException exception) {
                throw new VulnFlowClientException(
                        ClientFailureKind.RETRYABLE,
                        "VulnFlow returned an invalid ingestion response",
                        exception);
            }
        }
        throw classify(response.status(), true);
    }

    @Override
    public AgentClaim claimScan(String agentId, AgentHeartbeat heartbeat) {
        HttpResult response = jsonPost("api/v1/agents/" + encode(agentId) + "/scan-requests/claim", heartbeat);
        if (response.status() == 200 && response.body().length == 0) return null;
        if (response.status() == 200) {
            try { return objectMapper.readValue(response.body(), AgentClaim.class); }
            catch (IOException exception) { throw new VulnFlowClientException(ClientFailureKind.RETRYABLE,"VulnFlow returned an invalid claim",exception); }
        }
        throw classify(response.status(), false);
    }

    @Override public void heartbeat(String agentId, AgentHeartbeat heartbeat) { requireNoContent(jsonPost("api/v1/agents/"+encode(agentId)+"/heartbeat",heartbeat)); }
    @Override public void startScan(String agentId, UUID requestId, UUID claimToken) { requireNoContent(jsonPost("api/v1/agents/"+encode(agentId)+"/scan-requests/"+requestId+"/start",Map.of("claimToken",claimToken))); }
    @Override public void failScan(String agentId, UUID requestId, UUID claimToken, String safeError) { requireNoContent(jsonPost("api/v1/agents/"+encode(agentId)+"/scan-requests/"+requestId+"/fail",Map.of("claimToken",claimToken,"safeError",safeError==null?"Scan failed":safeError))); }

    private HttpResult jsonPost(String relative,Object value) {
        try {
            HttpRequest request=HttpRequest.newBuilder(endpoint(relative)).timeout(requestTimeout).header("X-API-Key",apiKey)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(value))).build();
            return send(request);
        } catch(IOException exception){throw new IllegalStateException("Agent request could not be serialized",exception);}
    }
    private void requireNoContent(HttpResult response){if(response.status()!=204&&response.status()!=200)throw classify(response.status(),false);}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}

    private HttpResult send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new VulnFlowClientException(
                            ClientFailureKind.RETRYABLE,
                            "VulnFlow response exceeded the agent limit");
                }
                return new HttpResult(response.statusCode(), body);
            }
        } catch (VulnFlowClientException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VulnFlowClientException(
                    ClientFailureKind.RETRYABLE,
                    "VulnFlow is temporarily unreachable",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VulnFlowClientException(
                    ClientFailureKind.RETRYABLE,
                    "VulnFlow request was interrupted",
                    exception);
        }
    }

    private VulnFlowClientException classify(int status, boolean upload) {
        if (status == 401 || status == 403) {
            return new VulnFlowClientException(
                    ClientFailureKind.CONFIGURATION,
                    "VulnFlow rejected the configured API credentials");
        }
        if (upload && status == 404) {
            return new VulnFlowClientException(
                    ClientFailureKind.ASSET_NOT_FOUND,
                    "VulnFlow no longer recognizes the resolved asset");
        }
        if (status >= 400 && status < 500) {
            return new VulnFlowClientException(
                    ClientFailureKind.PERMANENT,
                    "VulnFlow rejected the request with HTTP " + status);
        }
        return new VulnFlowClientException(
                ClientFailureKind.RETRYABLE,
                "VulnFlow failed temporarily with HTTP " + status);
    }

    private URI endpoint(String relative) {
        return apiUrl.resolve(relative);
    }

    private UUID uuidOrNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : UUID.fromString(value);
    }

    private record HttpResult(int status, byte[] body) {
    }
}
