package com.enterprise.textract.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.enterprise.textract.lambda.util.ExcelConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lambda handler triggered by S3 ObjectCreated events on the uploads bucket.
 *
 * Workflow:
 * 1. Parse S3 event → extract bucket + key
 * 2. Update DynamoDB job → PROCESSING
 * 3. Call Textract (async for PDFs, sync for images)
 * 4. Convert result blocks → Excel workbook
 * 5. Upload Excel → results bucket
 * 6. Update DynamoDB job → COMPLETED
 */
public class TextractLambdaHandler implements RequestHandler<S3Event, String> {

    private static final Logger log = LoggerFactory.getLogger(TextractLambdaHandler.class);

    private static final String RESULTS_BUCKET = System.getenv("RESULTS_BUCKET");
    private static final String DYNAMODB_TABLE = System.getenv("DYNAMODB_TABLE");
    private static final String AWS_REGION_NAME = System.getenv("AWS_REGION_NAME") != null
            ? System.getenv("AWS_REGION_NAME")
            : "us-east-1";

    // SDK clients (static for Lambda warm-start reuse)
    private static final S3Client s3Client;
    private static final TextractClient textractClient;
    private static final DynamoDbClient dynamoDbClient;

    static {
        Region region = Region.of(AWS_REGION_NAME);
        s3Client = S3Client.builder().region(region).build();
        textractClient = TextractClient.builder().region(region).build();
        dynamoDbClient = DynamoDbClient.builder().region(region).build();
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        log.info("Received S3 event with {} records", s3Event.getRecords().size());

        for (S3EventNotification.S3EventNotificationRecord record : s3Event.getRecords()) {
            String uploadBucket = record.getS3().getBucket().getName();
            String objectKey = record.getS3().getObject().getUrlDecodedKey();

            log.info("Processing file: s3://{}/{}", uploadBucket, objectKey);

            // Derive ackId from key: uploads/{ackId}/filename
            String ackId = extractAckId(objectKey);
            if (ackId == null) {
                log.warn("Could not derive ackId from key: {}. Skipping.", objectKey);
                continue;
            }

            String fileName = extractFileName(objectKey);

            try {
                // Mark as PROCESSING
                updateDynamoStatus(ackId, "PROCESSING", null, null);

                // Determine file type and call Textract accordingly
                List<Block> blocks;
                if (objectKey.toLowerCase().endsWith(".pdf")) {
                    blocks = analyzeDocumentAsync(uploadBucket, objectKey, context);
                } else {
                    blocks = detectDocumentText(uploadBucket, objectKey);
                }

                log.info("Textract returned {} blocks for key={}", blocks.size(), objectKey);

                // Convert to Excel
                byte[] excelBytes = ExcelConverter.convert(blocks, fileName);

                // Upload Excel to results bucket
                String baseName = fileName.replaceAll("\\.[^.]+$", "");
                String resultKey = "results/" + baseName + ".xlsx";
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(RESULTS_BUCKET)
                                .key(resultKey)
                                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                .build(),
                        RequestBody.fromBytes(excelBytes));
                log.info("Excel uploaded: s3://{}/{}", RESULTS_BUCKET, resultKey);

                // Mark as COMPLETED
                updateDynamoStatus(ackId, "COMPLETED", resultKey, null);

            } catch (Exception e) {
                log.error("Processing failed for ackId={}, key={}", ackId, objectKey, e);
                updateDynamoStatus(ackId, "FAILED", null, e.getMessage());
            }
        }

        return "OK";
    }

    // ─── Textract: Sync (images) ────────────────────────────────────────────
    private List<Block> detectDocumentText(String bucket, String key) {
        log.info("Calling DetectDocumentText (sync) for s3://{}/{}", bucket, key);

        DetectDocumentTextResponse response = textractClient.detectDocumentText(
                DetectDocumentTextRequest.builder()
                        .document(Document.builder()
                                .s3Object(S3Object.builder()
                                        .bucket(bucket)
                                        .name(key)
                                        .build())
                                .build())
                        .build());

        return response.blocks();
    }

    // ─── Textract: Async (PDFs, multi-page) ────────────────────────────────
    private List<Block> analyzeDocumentAsync(String bucket, String key, Context context) {
        log.info("Calling StartDocumentAnalysis (async) for s3://{}/{}", bucket, key);

        StartDocumentAnalysisResponse startResponse = textractClient.startDocumentAnalysis(
                StartDocumentAnalysisRequest.builder()
                        .documentLocation(DocumentLocation.builder()
                                .s3Object(S3Object.builder()
                                        .bucket(bucket)
                                        .name(key)
                                        .build())
                                .build())
                        .featureTypes(FeatureType.TABLES, FeatureType.FORMS)
                        .build());

        String jobId = startResponse.jobId();
        log.info("Textract async job started: jobId={}", jobId);

        // Poll for completion
        return pollForCompletion(jobId, context);
    }

    private List<Block> pollForCompletion(String jobId, Context context) {
        List<Block> allBlocks = new ArrayList<>();
        String nextToken = null;
        int pollCount = 0;

        while (true) {
            try {
                Thread.sleep(5000); // 5s between polls
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted", e);
            }

            GetDocumentAnalysisRequest.Builder requestBuilder = GetDocumentAnalysisRequest.builder()
                    .jobId(jobId)
                    .maxResults(1000);
            if (nextToken != null) {
                requestBuilder.nextToken(nextToken);
            }

            GetDocumentAnalysisResponse response = textractClient.getDocumentAnalysis(requestBuilder.build());
            String status = response.jobStatusAsString();
            pollCount++;

            log.info("Poll #{}: jobId={}, status={}", pollCount, jobId, status);

            if ("SUCCEEDED".equals(status)) {
                allBlocks.addAll(response.blocks());
                nextToken = response.nextToken();

                // Page through all results
                while (nextToken != null) {
                    GetDocumentAnalysisResponse nextPage = textractClient.getDocumentAnalysis(
                            GetDocumentAnalysisRequest.builder()
                                    .jobId(jobId)
                                    .nextToken(nextToken)
                                    .maxResults(1000)
                                    .build());
                    allBlocks.addAll(nextPage.blocks());
                    nextToken = nextPage.nextToken();
                }

                log.info("Textract job completed: {} total blocks", allBlocks.size());
                return allBlocks;

            } else if ("FAILED".equals(status)) {
                throw new RuntimeException("Textract job FAILED: " + jobId +
                        " — " + response.statusMessage());

            } else if ("IN_PROGRESS".equals(status) || "PARTIAL_SUCCESS".equals(status)) {
                // Check remaining Lambda time — leave 60s buffer
                if (context.getRemainingTimeInMillis() < 60_000) {
                    throw new RuntimeException("Lambda timeout approaching. Textract job still IN_PROGRESS: " + jobId);
                }
                // Continue polling
            } else {
                throw new RuntimeException("Unexpected Textract job status: " + status);
            }
        }
    }

    // ─── DynamoDB ───────────────────────────────────────────────────────────
    private void updateDynamoStatus(String ackId, String status,
            String resultKey, String errorMessage) {
        if (DYNAMODB_TABLE == null) {
            log.warn("DYNAMODB_TABLE env var not set, skipping DynamoDB update");
            return;
        }

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":status", attr(status));
        expressionValues.put(":updatedAt", attr(Instant.now().toString()));

        StringBuilder expr = new StringBuilder("SET #st = :status, updated_at = :updatedAt");
        Map<String, String> exprNames = new HashMap<>();
        exprNames.put("#st", "status");

        if (resultKey != null) {
            expressionValues.put(":resultKey", attr(resultKey));
            expr.append(", result_s3_key = :resultKey");
        }

        if (errorMessage != null) {
            String truncated = errorMessage.length() > 500
                    ? errorMessage.substring(0, 500)
                    : errorMessage;
            expressionValues.put(":errMsg", attr(truncated));
            expr.append(", error_message = :errMsg");
        }

        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(DYNAMODB_TABLE)
                    .key(Map.of("ack_id", attr(ackId)))
                    .updateExpression(expr.toString())
                    .expressionAttributeValues(expressionValues)
                    .expressionAttributeNames(exprNames)
                    .build());

            log.info("DynamoDB updated: ackId={}, status={}", ackId, status);
        } catch (Exception e) {
            log.error("Failed to update DynamoDB for ackId={}", ackId, e);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Extract ackId from S3 key format: uploads/{ackId}/filename
     */
    private String extractAckId(String key) {
        // Expected: uploads/{uuid}/{filename}
        String[] parts = key.split("/");
        if (parts.length >= 3 && "uploads".equals(parts[0])) {
            return parts[1];
        }
        return null;
    }

    private String extractFileName(String key) {
        String[] parts = key.split("/");
        return parts[parts.length - 1];
    }

    private AttributeValue attr(String value) {
        return AttributeValue.builder().s(value != null ? value : "").build();
    }
}
