package com.enterprise.textract.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private String ackId;
    private String fileName;
    private String status;
    private boolean duplicate;
    private String message;
    private String existingAckId;
}

// ─── inline DTOs (inner classes to reduce file count) ──────────────────
