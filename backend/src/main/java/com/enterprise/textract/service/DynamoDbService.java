package com.enterprise.textract.service;

import com.enterprise.textract.model.JobRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamoDbService {

    private final DynamoDbClient dynamoDbClient;

    @Value("${aws.dynamodb.table-name}")
    private String tableName;

    /**
     * Create a new job record with PENDING status.
     */
    public JobRecord createJob(String ackId, String fileName, String s3UploadKey) {
        String now = Instant.now().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ack_id", attr(ackId));
        item.put("file_name", attr(fileName));
        item.put("s3_upload_key", attr(s3UploadKey));
        item.put("status", attr("PENDING"));
        item.put("created_at", attr(now));
        item.put("updated_at", attr(now));
        item.put("duplicate", AttributeValue.builder().bool(false).build());

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

        log.info("Created job record: ackId={}, file={}", ackId, fileName);
        return mapToRecord(item);
    }

    /**
     * Fetch a job record by ackId.
     */
    public Optional<JobRecord> getJob(String ackId) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("ack_id", attr(ackId)))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mapToRecord(response.item()));
    }

    /**
     * Update job status and optionally the result S3 key.
     */
    public void updateStatus(String ackId, String status, String resultS3Key) {
        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":status", attr(status));
        expressionValues.put(":updatedAt", attr(Instant.now().toString()));

        String updateExpression = "SET #st = :status, updated_at = :updatedAt";
        Map<String, String> expressionNames = new HashMap<>();
        expressionNames.put("#st", "status");

        if (resultS3Key != null) {
            expressionValues.put(":resultKey", attr(resultS3Key));
            updateExpression += ", result_s3_key = :resultKey";
        }

        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("ack_id", attr(ackId)))
                .updateExpression(updateExpression)
                .expressionAttributeValues(expressionValues)
                .expressionAttributeNames(expressionNames)
                .build());

        log.info("Updated job status: ackId={}, status={}", ackId, status);
    }

    /**
     * Query jobs by filename using the FileNameIndex GSI.
     */
    public Optional<JobRecord> findByFileName(String fileName) {
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName("FileNameIndex")
                .keyConditionExpression("file_name = :fn")
                .expressionAttributeValues(Map.of(":fn", attr(fileName)))
                .limit(1)
                .build());

        if (response.items().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mapToRecord(response.items().get(0)));
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private AttributeValue attr(String value) {
        return AttributeValue.builder().s(value == null ? "" : value).build();
    }

    private JobRecord mapToRecord(Map<String, AttributeValue> item) {
        return JobRecord.builder()
                .ackId(str(item, "ack_id"))
                .fileName(str(item, "file_name"))
                .s3UploadKey(str(item, "s3_upload_key"))
                .resultS3Key(str(item, "result_s3_key"))
                .status(str(item, "status"))
                .createdAt(str(item, "created_at"))
                .updatedAt(str(item, "updated_at"))
                .errorMessage(str(item, "error_message"))
                .duplicate(item.containsKey("duplicate") &&
                        Boolean.TRUE.equals(item.get("duplicate").bool()))
                .build();
    }

    private String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : null;
    }
}
