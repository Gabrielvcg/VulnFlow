package com.vulnflow.aws.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsIngestionMessagePublisherTest {
    @Test
    void publishesTheVersionedContractAndCorrelationAttributesWithoutNetworkCalls() {
        SqsClient client = mock(SqsClient.class);
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        SqsIngestionMessagePublisher publisher = new SqsIngestionMessagePublisher(
                client, "https://sqs.invalid/queue", new IngestionEventJsonCodec());
        IngestionEventV1 event = new IngestionEventV1("1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "reports/a.json", "a".repeat(64), "TRIVY", Instant.now(), UUID.randomUUID());

        assertThat(publisher.publish(event)).isEqualTo("message-1");
        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(request.capture());
        assertThat(request.getValue().messageBody()).contains("\"eventVersion\":\"1\"");
        assertThat(request.getValue().messageAttributes()).containsKeys("eventId", "correlationId");
    }
}
