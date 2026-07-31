package com.vulnflow.agent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VulnFlowHttpClientTest {

    private HttpServer server;
    private URI baseUri;
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void resolvesAssetsAndSendsMultipartWithApiKey() throws Exception {
        AtomicReference<String> resolveApiKey = new AtomicReference<>();
        AtomicReference<String> uploadApiKey = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();
        AtomicReference<String> uploadBody = new AtomicReference<>();
        server.createContext("/api/v1/assets/resolve", exchange -> {
            resolveApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            respond(exchange, 201, "{\"id\":\"11111111-1111-1111-1111-111111111111\"}");
        });
        server.createContext("/api/v1/scans/trivy", exchange -> {
            uploadApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploadBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, """
                    {"scanId":"22222222-2222-2222-2222-222222222222",
                     "jobId":"33333333-3333-3333-3333-333333333333",
                     "outcome":"ACCEPTED"}
                    """);
        });
        VulnFlowHttpClient client = client(Duration.ofSeconds(2));

        AssetResolution resolution = client.resolveAsset(target());
        Path report = temporaryDirectory.resolve("report.json");
        Files.writeString(report, "{\"Results\":[]}");
        UploadReceipt receipt = client.uploadTrivyReport(resolution.assetId(), report);

        assertThat(resolution.created()).isTrue();
        assertThat(receipt.outcome()).isEqualTo("ACCEPTED");
        assertThat(resolveApiKey).hasValue("secret-api-key");
        assertThat(uploadApiKey).hasValue("secret-api-key");
        assertThat(uploadContentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(uploadBody.get()).contains("filename=\"report.json\"").contains("{\"Results\":[]}");
    }

    @Test
    void acceptsEveryCurrentSuccessfulIngestionStatus() throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> response = new AtomicReference<>();
        server.createContext("/api/v1/scans/trivy", exchange -> respond(
                exchange,
                status.get(),
                response.get()));
        Path report = temporaryDirectory.resolve("report.json");
        Files.writeString(report, "{}");
        VulnFlowHttpClient client = client(Duration.ofSeconds(2));

        record AcceptedResponse(int httpStatus, String outcome, boolean hasJob) {}
        var cases = java.util.List.of(
                new AcceptedResponse(202, "ACCEPTED", true),
                new AcceptedResponse(200, "DUPLICATE", false),
                new AcceptedResponse(202, "ALREADY_QUEUED", true),
                new AcceptedResponse(202, "ALREADY_PROCESSING", true),
                new AcceptedResponse(200, "DEAD_LETTER", true));

        for (AcceptedResponse accepted : cases) {
            status.set(accepted.httpStatus());
            String job = accepted.hasJob()
                    ? ",\"jobId\":\"33333333-3333-3333-3333-333333333333\""
                    : "";
            response.set("{\"scanId\":\"22222222-2222-2222-2222-222222222222\""
                    + job + ",\"outcome\":\"" + accepted.outcome() + "\"}");

            UploadReceipt receipt = client.uploadTrivyReport(java.util.UUID.randomUUID(), report);

            assertThat(receipt.outcome()).isEqualTo(accepted.outcome());
            assertThat(receipt.jobId() != null).isEqualTo(accepted.hasJob());
        }
    }

    @Test
    void classifiesAuthenticationNotFoundClientAndServerErrors() throws Exception {
        AtomicInteger status = new AtomicInteger(401);
        server.createContext("/api/v1/scans/trivy", exchange -> respond(exchange, status.get(), "{}"));
        Path report = temporaryDirectory.resolve("report.json");
        Files.writeString(report, "{}");
        VulnFlowHttpClient client = client(Duration.ofSeconds(2));

        assertKind(client, report, ClientFailureKind.CONFIGURATION);
        status.set(404);
        assertKind(client, report, ClientFailureKind.ASSET_NOT_FOUND);
        status.set(422);
        assertKind(client, report, ClientFailureKind.PERMANENT);
        status.set(503);
        assertKind(client, report, ClientFailureKind.RETRYABLE);
    }

    @Test
    void classifiesReadTimeoutAndUnavailableBackendAsRetryable() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/api/v1/assets/resolve", exchange -> {
            try {
                release.await(2, TimeUnit.SECONDS);
                respond(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        VulnFlowHttpClient timeoutClient = client(Duration.ofMillis(50));
        assertThatThrownBy(() -> timeoutClient.resolveAsset(target()))
                .isInstanceOfSatisfying(VulnFlowClientException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(ClientFailureKind.RETRYABLE));
        release.countDown();

        VulnFlowHttpClient unavailable = new VulnFlowHttpClient(
                URI.create("http://127.0.0.1:1/"),
                "secret-api-key",
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                AgentObjectMapper.create());
        assertThatThrownBy(() -> unavailable.resolveAsset(target()))
                .isInstanceOfSatisfying(VulnFlowClientException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(ClientFailureKind.RETRYABLE));
    }

    private void assertKind(VulnFlowHttpClient client, Path report, ClientFailureKind kind) {
        assertThatThrownBy(() -> client.uploadTrivyReport(java.util.UUID.randomUUID(), report))
                .isInstanceOfSatisfying(VulnFlowClientException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(kind));
    }

    private VulnFlowHttpClient client(Duration requestTimeout) {
        return new VulnFlowHttpClient(
                baseUri,
                "secret-api-key",
                Duration.ofSeconds(1),
                requestTimeout,
                AgentObjectMapper.create());
    }

    private ScanTarget target() {
        return new ScanTarget("alpine", TargetType.CONTAINER_IMAGE, "alpine:3.15");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
