package com.vulnflow.processing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PayloadIntegrityVerifier {
    public void verify(byte[] content, String expectedHash) {
        byte[] expected = decodeExpectedHash(expectedHash);
        byte[] actual = sha256(content);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new PayloadIntegrityException("Stored report payload integrity verification failed");
        }
    }

    private byte[] decodeExpectedHash(String expectedHash) {
        try {
            if (expectedHash == null || expectedHash.length() != 64) {
                throw new IllegalArgumentException("Invalid SHA-256 length");
            }
            return HexFormat.of().parseHex(expectedHash);
        } catch (IllegalArgumentException exception) {
            throw new PayloadIntegrityException("Stored report payload integrity verification failed");
        }
    }

    private byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
