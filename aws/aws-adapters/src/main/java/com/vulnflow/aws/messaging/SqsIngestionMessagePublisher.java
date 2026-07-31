package com.vulnflow.aws.messaging;

import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.port.IngestionMessagePublisher;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

public final class SqsIngestionMessagePublisher implements IngestionMessagePublisher {
    private final SqsClient client;
    private final String queueUrl;
    private final IngestionEventJsonCodec codec;

    public SqsIngestionMessagePublisher(SqsClient client, String queueUrl, IngestionEventJsonCodec codec) {
        this.client = Objects.requireNonNull(client, "client");
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("queueUrl must not be blank");
        }
        this.queueUrl = queueUrl;
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public String publish(IngestionEventV1 event) {
        Objects.requireNonNull(event, "event");
        try {
            SendMessageResponse response = client.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(codec.serialize(event))
                    .messageAttributes(Map.of(
                            "eventVersion", stringAttribute(event.eventVersion()),
                            "eventId", stringAttribute(event.eventId().toString()),
                            "correlationId", stringAttribute(event.correlationId().toString())))
                    .build());
            return response.messageId();
        } catch (SdkException exception) {
            throw new IngestionMessagePublishException("The ingestion event could not be published to SQS", exception);
        }
    }

    private MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
