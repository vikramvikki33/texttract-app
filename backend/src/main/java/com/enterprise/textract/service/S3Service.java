package com.enterprise.textract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    /**
     * Upload an object to the given S3 bucket.
     */
    public void uploadFile(String bucket, String key, InputStream inputStream,
            String contentType, long contentLength) {
        log.info("Uploading to s3://{}/{}", bucket, key);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(inputStream, contentLength));
        log.info("Upload complete: s3://{}/{}", bucket, key);
    }

    /**
     * Upload raw bytes.
     */
    public void uploadBytes(String bucket, String key, byte[] data, String contentType) {
        log.info("Uploading bytes to s3://{}/{}", bucket, key);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
    }

    /**
     * Download an object from S3.
     */
    public ResponseInputStream<GetObjectResponse> downloadFile(String bucket, String key) {
        log.info("Downloading s3://{}/{}", bucket, key);
        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build());
    }

    /**
     * Check whether an S3 key exists in the given bucket.
     */
    public boolean objectExists(String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception e) {
            return false;
        }
    }

    /**
     * Generate a pre-signed URL for direct download (valid for 1 hour).
     */
    public String generatePresignedUrl(String bucket, String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(r -> r.bucket(bucket).key(key))
                .build();
        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    /**
     * Delete an object from S3.
     */
    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
        log.info("Deleted s3://{}/{}", bucket, key);
    }
}
