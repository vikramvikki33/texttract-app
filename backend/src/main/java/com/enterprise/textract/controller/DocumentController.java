package com.enterprise.textract.controller;

import com.enterprise.textract.model.JobRecord;
import com.enterprise.textract.model.UploadResponse;
import com.enterprise.textract.service.DynamoDbService;
import com.enterprise.textract.service.ExcelService;
import com.enterprise.textract.service.S3Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Document API", description = "Document upload, status polling, and result retrieval")
public class DocumentController {

    private final S3Service s3Service;
    private final DynamoDbService dynamoDbService;
    private final ExcelService excelService;

    @Value("${aws.s3.upload-bucket}")
    private String uploadBucket;

    @Value("${aws.s3.results-bucket}")
    private String resultsBucket;

    // ─── Allowed content types ─────────────────────────────────────────────
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "image/jpeg", "image/jpg", "image/png",
            "image/tiff", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".jpg", ".jpeg", ".png", ".tiff", ".tif", ".webp");

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/documents/upload
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document for Textract analysis")
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file) {

        // Validate file
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    UploadResponse.builder().message("File is empty").build());
        }

        String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(), "unknown");
        String lowerName = originalFileName.toLowerCase();

        boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (!validExt) {
            return ResponseEntity.badRequest().body(
                    UploadResponse.builder()
                            .message("Unsupported file type. Allowed: PDF, JPG, PNG, TIFF")
                            .build());
        }

        // ── Duplicate detection ────────────────────────────────────────────
        // Check if a completed result already exists in S3 for this filename
        String resultKey = buildResultKey(originalFileName);
        if (s3Service.objectExists(resultsBucket, resultKey)) {
            // Also look up existing job record via filename GSI
            Optional<JobRecord> existing = dynamoDbService.findByFileName(originalFileName);
            if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
                log.info("Duplicate detected for file: {}", originalFileName);
                return ResponseEntity.ok(UploadResponse.builder()
                        .ackId(existing.get().getAckId())
                        .fileName(originalFileName)
                        .status("COMPLETED")
                        .duplicate(true)
                        .existingAckId(existing.get().getAckId())
                        .message("This document was previously analysed. " +
                                "You can view the existing result or click Reprocess to run again.")
                        .build());
            }
        }

        // ── Upload to S3 ───────────────────────────────────────────────────
        String ackId = UUID.randomUUID().toString();
        String s3Key = buildUploadKey(ackId, originalFileName);

        try {
            s3Service.uploadFile(
                    uploadBucket, s3Key,
                    file.getInputStream(),
                    resolveContentType(file, originalFileName),
                    file.getSize());
        } catch (Exception e) {
            log.error("Failed to upload file to S3", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    UploadResponse.builder().message("Upload failed: " + e.getMessage()).build());
        }

        // ── Create DynamoDB record ─────────────────────────────────────────
        dynamoDbService.createJob(ackId, originalFileName, s3Key);

        log.info("Document queued for processing: ackId={}, file={}", ackId, originalFileName);

        return ResponseEntity.ok(UploadResponse.builder()
                .ackId(ackId)
                .fileName(originalFileName)
                .status("PENDING")
                .duplicate(false)
                .message("Document uploaded successfully. Use the acknowledgment ID to track progress.")
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/documents/status/{ackId}
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/status/{ackId}")
    @Operation(summary = "Get the processing status of a document by acknowledgment ID")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String ackId) {
        Optional<JobRecord> jobOpt = dynamoDbService.getJob(ackId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Job not found for ackId: " + ackId));
        }

        JobRecord job = jobOpt.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ackId", job.getAckId());
        response.put("fileName", job.getFileName());
        response.put("status", job.getStatus());
        response.put("createdAt", job.getCreatedAt());
        response.put("updatedAt", job.getUpdatedAt());
        response.put("hasResult", job.getResultS3Key() != null && !job.getResultS3Key().isBlank());
        if (job.getErrorMessage() != null) {
            response.put("error", job.getErrorMessage());
        }

        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/documents/result/{ackId}
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/result/{ackId}")
    @Operation(summary = "Get the extracted text/table results for a completed job")
    public ResponseEntity<Map<String, Object>> getResult(@PathVariable String ackId) {
        Optional<JobRecord> jobOpt = dynamoDbService.getJob(ackId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Job not found for ackId: " + ackId));
        }

        JobRecord job = jobOpt.get();

        if (!"COMPLETED".equals(job.getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "ackId", ackId,
                            "status", job.getStatus(),
                            "message", "Document is still being processed. Current status: " + job.getStatus()));
        }

        String resultKey = job.getResultS3Key();
        if (resultKey == null || resultKey.isBlank()) {
            // Fallback: derive result key from filename
            resultKey = buildResultKey(job.getFileName());
        }

        if (!s3Service.objectExists(resultsBucket, resultKey)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Result file not found in S3. Processing may have failed."));
        }

        try {
            Map<String, List<Map<String, String>>> sheets = excelService
                    .excelToAllSheets(s3Service.downloadFile(resultsBucket, resultKey));

            String downloadUrl = s3Service.generatePresignedUrl(resultsBucket, resultKey);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ackId", ackId);
            response.put("fileName", job.getFileName());
            response.put("status", "COMPLETED");
            response.put("sheets", sheets);
            response.put("downloadUrl", downloadUrl);
            response.put("createdAt", job.getCreatedAt());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read result for ackId={}", ackId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to read result: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/documents/reprocess/{ackId}
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/reprocess/{ackId}")
    @Operation(summary = "Re-trigger Textract processing for an existing job (overwrites previous result)")
    public ResponseEntity<Map<String, Object>> reprocess(@PathVariable String ackId) {
        Optional<JobRecord> jobOpt = dynamoDbService.getJob(ackId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Job not found for ackId: " + ackId));
        }

        JobRecord job = jobOpt.get();

        // Verify original upload still exists
        if (!s3Service.objectExists(uploadBucket, job.getS3UploadKey())) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Original upload file no longer exists in S3"));
        }

        // Delete old result if present
        String resultKey = buildResultKey(job.getFileName());
        if (s3Service.objectExists(resultsBucket, resultKey)) {
            s3Service.deleteObject(resultsBucket, resultKey);
            log.info("Deleted old result for reprocessing: {}", resultKey);
        }

        // Reset status to PENDING — this triggers Lambda via S3 copy-in-place
        // We re-upload the original object (copy to itself) to fire the S3 event
        try {
            // Download original file and re-upload with same key to trigger Lambda
            var originalBytes = s3Service.downloadFile(uploadBucket, job.getS3UploadKey()).readAllBytes();
            String contentType = resolveContentTypeFromKey(job.getS3UploadKey());
            s3Service.uploadBytes(uploadBucket, job.getS3UploadKey(), originalBytes, contentType);
        } catch (Exception e) {
            log.error("Failed to re-trigger processing for ackId={}", ackId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to re-trigger processing: " + e.getMessage()));
        }

        // Reset DynamoDB status
        dynamoDbService.updateStatus(ackId, "PENDING", null);

        return ResponseEntity.ok(Map.of(
                "ackId", ackId,
                "status", "PENDING",
                "message", "Reprocessing started. Poll /status/" + ackId + " for updates."));
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/documents/download/{ackId}
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/download/{ackId}")
    @Operation(summary = "Get a pre-signed download URL for the Excel result file")
    public ResponseEntity<Map<String, String>> getDownloadUrl(@PathVariable String ackId) {
        Optional<JobRecord> jobOpt = dynamoDbService.getJob(ackId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Job not found"));
        }
        JobRecord job = jobOpt.get();
        if (!"COMPLETED".equals(job.getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", job.getStatus(), "message", "Not yet completed"));
        }
        String resultKey = job.getResultS3Key() != null ? job.getResultS3Key() : buildResultKey(job.getFileName());
        String url = s3Service.generatePresignedUrl(resultsBucket, resultKey);
        return ResponseEntity.ok(Map.of("downloadUrl", url, "expiresIn", "1 hour"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private String buildUploadKey(String ackId, String fileName) {
        return "uploads/" + ackId + "/" + fileName;
    }

    private String buildResultKey(String fileName) {
        // Result key uses sanitized filename — must match what Lambda writes
        String baseName = fileName.replaceAll("\\.[^.]+$", ""); // strip extension
        return "results/" + baseName + ".xlsx";
    }

    private String resolveContentType(MultipartFile file, String fileName) {
        if (file.getContentType() != null && ALLOWED_TYPES.contains(file.getContentType())) {
            return file.getContentType();
        }
        return resolveContentTypeFromKey(fileName);
    }

    private String resolveContentTypeFromKey(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".pdf"))
            return "application/pdf";
        if (lower.endsWith(".png"))
            return "image/png";
        if (lower.endsWith(".tiff") || lower.endsWith(".tif"))
            return "image/tiff";
        return "image/jpeg";
    }
}
