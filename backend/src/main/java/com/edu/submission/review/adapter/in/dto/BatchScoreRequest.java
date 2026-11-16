package com.edu.submission.review.adapter.in.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 批量打分请求。
 * <ul>
 *   <li>submissionIds：待打分提交列表，≤200（超限在 Service 层抛 422 BATCH_TOO_MANY）</li>
 *   <li>rule：ALL_MAX（量规总分或 100）/ BY_CHECK_LEVEL（PASS 90 / WARNING 75 / SEVERE 60）/ FIXED</li>
 *   <li>fixedScore：仅 FIXED 必填，0-100</li>
 *   <li>reason：批量打分原因，≥8 字符</li>
 * </ul>
 */
public record BatchScoreRequest(
        List<UUID> submissionIds,
        String rule,
        BigDecimal fixedScore,
        String reason
) {
}
