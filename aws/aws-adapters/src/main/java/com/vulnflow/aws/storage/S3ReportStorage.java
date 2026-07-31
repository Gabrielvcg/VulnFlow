package com.vulnflow.aws.storage;

import com.vulnflow.processing.port.PayloadNotFoundException;
import com.vulnflow.processing.port.ReportStorage;
import com.vulnflow.processing.port.ReportStorageException;
import com.vulnflow.processing.port.TransientReportStorageException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.io.IOException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3ReportStorage implements ReportStorage {
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final String SHA_METADATA = "vulnflow-sha256";
    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final long maxPayloadBytes;

    public S3ReportStorage(S3Client client, String bucket, String prefix, long maxPayloadBytes) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = requireText(bucket, "bucket");
        this.prefix = normalizePrefix(prefix);
        if (maxPayloadBytes < 1 || maxPayloadBytes > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("maxPayloadBytes must fit a bounded in-memory read");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    public String store(UUID scanId, byte[] content) {
        Objects.requireNonNull(scanId, "scanId");
        Objects.requireNonNull(content, "content");
        ensureAllowedSize(content.length);
        String key = prefix + scanId + "/" + UUID.randomUUID() + ".json";
        byte[] digest = sha256(content);
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/json")
                            .contentLength((long) content.length)
                            .checksumSHA256(Base64.getEncoder().encodeToString(digest))
                            .metadata(Map.of(SHA_METADATA, HexFormat.of().formatHex(digest)))
                            .build(),
                    RequestBody.fromBytes(content));
            return key;
        } catch (SdkException exception) {
            throw new TransientReportStorageException("The report payload could not be stored in S3", exception);
        }
    }

    @Override
    public byte[] load(String payloadKey) {
        String key = validateKey(payloadKey);
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).checksumMode("ENABLED").build());
            byte[] content;
            try (response) {
                content = response.readNBytes((int) maxPayloadBytes + 1);
            }
            ensureAllowedSize(content.length);
            String expected = response.response().metadata().get(SHA_METADATA);
            if (expected == null || !MessageDigest.isEqual(
                    HexFormat.of().parseHex(expected), sha256(content))) {
                throw new ReportStorageException("The S3 report payload checksum is missing or invalid", null);
            }
            return content;
        } catch (NoSuchKeyException exception) {
            throw new PayloadNotFoundException("The report payload does not exist", exception);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new PayloadNotFoundException("The report payload does not exist", exception);
            }
            throw new TransientReportStorageException("The report payload could not be read from S3", exception);
        } catch (IllegalArgumentException exception) {
            throw new ReportStorageException("The S3 report payload checksum is invalid", exception);
        } catch (IOException exception) {
            throw new TransientReportStorageException("The report payload could not be read from S3", exception);
        } catch (SdkException exception) {
            throw new TransientReportStorageException("The report payload could not be read from S3", exception);
        }
    }

    @Override
    public void delete(String payloadKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(validateKey(payloadKey)).build());
        } catch (SdkException exception) {
            throw new TransientReportStorageException("The report payload could not be deleted from S3", exception);
        }
    }

    @Override
    public boolean exists(String payloadKey) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(validateKey(payloadKey)).build());
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw new TransientReportStorageException("The S3 report payload could not be checked", exception);
        } catch (SdkException exception) {
            throw new TransientReportStorageException("The S3 report payload could not be checked", exception);
        }
    }

    private String validateKey(String key) {
        String value = requireText(key, "payloadKey");
        if (!value.startsWith(prefix) || value.contains("..") || value.contains("\\") || value.startsWith("/")) {
            throw new IllegalArgumentException("payloadKey is outside the configured S3 prefix");
        }
        return value;
    }

    private void ensureAllowedSize(long size) {
        if (size < 1 || size > maxPayloadBytes) {
            throw new IllegalArgumentException("Report payload size is outside the configured limit");
        }
    }

    private static String normalizePrefix(String value) {
        String prefix = requireText(value, "prefix");
        if (!SAFE_PREFIX.matcher(prefix).matches() || prefix.startsWith("/") || prefix.contains("..") || prefix.contains("\\")) {
            throw new IllegalArgumentException("prefix is not a safe logical S3 prefix");
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
