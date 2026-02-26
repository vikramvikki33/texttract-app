package com.enterprise.textract.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRecord {
    private String ackId;
    private String fileName;
    private String s3UploadKey;
    private String resultS3Key;
    private String status; // PENDING | PROCESSING | COMPLETED | FAILED
    private String createdAt;
    private String updatedAt;
    private boolean duplicate;
    private String errorMessage;
}
