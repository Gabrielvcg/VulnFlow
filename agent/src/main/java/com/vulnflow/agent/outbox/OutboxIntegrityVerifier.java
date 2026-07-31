package com.vulnflow.agent.outbox;

import com.vulnflow.agent.shared.Hashes;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class OutboxIntegrityVerifier {

    public void verify(OutboxItem item, Path report) {
        try {
            byte[] expected = HexFormat.of().parseHex(item.sha256());
            byte[] actual = HexFormat.of().parseHex(Hashes.sha256(report));
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new OutboxIntegrityException("Stored outbox report failed integrity verification");
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw new OutboxIntegrityException("Stored outbox report failed integrity verification", exception);
        }
    }
}
