package com.kista.adapter.in.web.dto;

import com.kista.domain.model.AdminStats;
import io.swagger.v3.oas.annotations.media.Schema;

public record AdminDashboardResponse(
        @Schema(description = "전체 사용자 수") long totalUsers,
        @Schema(description = "승인 대기 수") long pendingCount,
        @Schema(description = "승인된 수") long activeCount,
        @Schema(description = "거절된 수") long rejectedCount,
        @Schema(description = "전체 계좌 수") long totalAccounts
) {
    public static AdminDashboardResponse from(AdminStats stats) {
        return new AdminDashboardResponse(stats.totalUsers(), stats.pendingCount(),
                stats.activeCount(), stats.rejectedCount(), stats.totalAccounts());
    }
}
