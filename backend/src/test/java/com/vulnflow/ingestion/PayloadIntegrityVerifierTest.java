package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnflow.processing.PayloadIntegrityException;
import com.vulnflow.processing.PayloadIntegrityVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PayloadIntegrityVerifierTest {

    private final PayloadIntegrityVerifier verifier = new PayloadIntegrityVerifier();

    @Test
    void acceptsContentMatchingTheExpectedSha256() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> verifier.verify(content, sha256(content))).doesNotThrowAnyException();
    }

    @Test
    void rejectsModifiedContentAndInvalidExpectedHashes() throws Exception {
        byte[] original = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] modified = "modified".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(modified, sha256(original)))
                .isInstanceOf(PayloadIntegrityException.class)
                .hasMessage("Stored report payload integrity verification failed");
        assertThatThrownBy(() -> verifier.verify(original, "not-a-sha256"))
                .isInstanceOf(PayloadIntegrityException.class);
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
