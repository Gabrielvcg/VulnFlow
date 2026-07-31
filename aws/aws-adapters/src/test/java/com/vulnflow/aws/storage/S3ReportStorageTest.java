package com.vulnflow.aws.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnflow.processing.port.TransientReportStorageException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3ReportStorageTest {
    @Test
    void generatesAnInternalSafeKeyAndSendsChecksumWithoutNetworkCalls() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3ReportStorage storage = new S3ReportStorage(client, "private-bucket", "reports", 1024);
        UUID scanId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        String key = storage.store(scanId, "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(key).matches("reports/" + scanId + "/[0-9a-f-]{36}\\.json");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().checksumSHA256()).isNotBlank();
        assertThat(request.getValue().metadata()).containsKey("vulnflow-sha256");
    }

    @Test
    void rejectsUnsafePrefixes() {
        assertThatThrownBy(() -> new S3ReportStorage(mock(S3Client.class), "bucket", "../reports", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsSdkDownloadFailuresWithoutCallingAws() {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("offline"));
        S3ReportStorage storage = new S3ReportStorage(client, "bucket", "reports", 1024);
        assertThatThrownBy(() -> storage.load("reports/scan/report.json"))
                .isInstanceOf(TransientReportStorageException.class);
    }
}
