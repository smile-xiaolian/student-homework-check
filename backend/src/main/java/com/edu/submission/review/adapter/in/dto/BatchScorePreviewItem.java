package com.edu.submission.review.adapter.in.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** 批量打分预览项：单个提交的打分前后对照。 */
public record BatchScorePreviewItem(
        UUID submissionId,
        String submitterName,
        String checkLevel,
        BigDecimal before,
        BigDecimal after
) {
}
