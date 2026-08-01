package com.vulnflow.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@JsonPropertyOrder({
        "eventVersion", "eventId", "scanId", "assetId", "payloadKey",
        "contentHash", "scanner", "createdAt", "correlationId"
})
public record IngestionEventV1(
        String eventVersion,
        UUID eventId,
        UUID scanId,
        UUID assetId,
        String payloadKey,
        String contentHash,
        String scanner,
        Instant createdAt,
        UUID correlationId) {
    public static final String VERSION = "1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,1023}");

    @JsonCreator
    public IngestionEventV1(
            @JsonProperty(value = "eventVersion", required = true) String eventVersion,
            @JsonProperty(value = "eventId", required = true) UUID eventId,
            @JsonProperty(value = "scanId", required = true) UUID scanId,
            @JsonProperty(value = "assetId", required = true) UUID assetId,
            @JsonProperty(value = "payloadKey", required = true) String payloadKey,
            @JsonProperty(value = "contentHash", required = true) String contentHash,
            @JsonProperty(value = "scanner", required = true) String scanner,
            @JsonProperty(value = "createdAt", required = true) Instant createdAt,
            @JsonProperty(value = "correlationId", required = true) UUID correlationId) {
        if (!VERSION.equals(eventVersion)) {
            throw new UnsupportedEventVersionException(eventVersion);
        }
        this.eventVersion = eventVersion;
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.scanId = Objects.requireNonNull(scanId, "scanId");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.payloadKey = validatePayloadKey(payloadKey);
        this.contentHash = validateHash(contentHash);
        this.scanner = validateScanner(scanner);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    private static String validatePayloadKey(String value) {
        Objects.requireNonNull(value, "payloadKey");
        if (!SAFE_KEY.matcher(value).matches()
                || value.startsWith("/")
                || value.contains("\\")
                || Arrays.stream(value.split("/", -1))
                        .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("payloadKey is not a safe logical key");
        }
        return value;
    }

    private static String validateHash(String value) {
        Objects.requireNonNull(value, "contentHash");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException("contentHash must be a SHA-256 hexadecimal value");
        }
        return normalized;
    }

    private static String validateScanner(String value) {
        Objects.requireNonNull(value, "scanner");
        if (!"TRIVY".equals(value)) {
            throw new IllegalArgumentException("scanner must be TRIVY for event version 1");
        }
        return value;
    }
}
