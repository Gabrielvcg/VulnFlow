package com.vulnflow.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestionEventContractTest {
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SCAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private final IngestionEventJsonCodec codec = new IngestionEventJsonCodec();

    @Test
    void serializationIsStableAndRoundTrips() {
        IngestionEventV1 event = validEvent();
        String json = codec.serialize(event);
        assertThat(json).isEqualTo("{\"eventVersion\":\"1\",\"eventId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"scanId\":\"00000000-0000-0000-0000-000000000002\",\"assetId\":\"00000000-0000-0000-0000-000000000003\","
                + "\"payloadKey\":\"reports/2026/report.json\",\"contentHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"scanner\":\"TRIVY\",\"createdAt\":\"2026-07-31T10:15:30Z\","
                + "\"correlationId\":\"00000000-0000-0000-0000-000000000004\"}");
        assertThat(codec.deserialize(json)).isEqualTo(event);
    }

    @Test
    void rejectsUnknownVersion() {
        assertThatThrownBy(() -> codec.deserialize(codec.serialize(validEvent()).replace("\"1\"", "\"2\"")))
                .isInstanceOf(UnsupportedEventVersionException.class);
    }

    @Test
    void rejectsIncompleteEvent() {
        String incomplete = codec.serialize(validEvent()).replace(",\"correlationId\":\"" + CORRELATION_ID + "\"", "");
        assertThatThrownBy(() -> codec.deserialize(incomplete)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsPhysicalOrTraversingPayloadKeys() {
        assertThatThrownBy(() -> new IngestionEventV1("1", EVENT_ID, SCAN_ID, ASSET_ID,
                "reports/..", "a".repeat(64), "TRIVY",
                Instant.parse("2026-07-31T10:15:30Z"), CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IngestionEventV1 validEvent() {
        return new IngestionEventV1("1", EVENT_ID, SCAN_ID, ASSET_ID,
                "reports/2026/report.json", "a".repeat(64), "TRIVY",
                Instant.parse("2026-07-31T10:15:30Z"), CORRELATION_ID);
    }
}
