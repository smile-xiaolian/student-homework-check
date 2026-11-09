package com.edu.submission.submission.adapter.in.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SubmissionCreateRequest(
        @Size(max = 1000000, message = "在线文本不能超过 100 万字符")
        String onlineText,
        Boolean integrityConsent,
        List<UUID> fileObjectIds,
        @Size(max = 128, message = "幂等键过长")
        String idempotencyKey
) {
}

