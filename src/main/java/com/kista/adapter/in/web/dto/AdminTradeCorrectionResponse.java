package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminTradeCorrectionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 수동 체결 보정 응답 DTO
public record AdminTradeCorrectionResponse(
        @Schema(description = "대상 사용자 ID")
        UUID userId,
        @Schema(description = "대상 계좌 ID")
        UUID accountId,
        @Schema(description = "대상 전략 ID")
        UUID strategyId,
        @Schema(description = "처리된 체결 건수")
        int processedCount,
        @Schema(description = "보정 후 최종 보유 수량")
        int finalHoldings,
        @Schema(description = "보정 후 최종 평단가")
        BigDecimal finalAvgPrice,
        @Schema(description = "보정 후 최종 예수금 (USD)")
        BigDecimal finalUsdDeposit,
        @Schema(description = "보정 후 전략 상태", example = "ACTIVE")
        String strategyStatus,
        @Schema(description = "보정으로 사이클이 종료됐는지 여부")
        boolean cycleEnded,
        @Schema(description = "사이클 종료일 (cycleEnded=false면 null)")
        LocalDate cycleEndDate
) {
    public static AdminTradeCorrectionResponse from(AdminTradeCorrectionResult result) {
        return new AdminTradeCorrectionResponse(
                result.userId(),
                result.accountId(),
                result.strategyId(),
                result.processedCount(),
                result.finalHoldings(),
                result.finalAvgPrice(),
                result.finalUsdDeposit(),
                result.strategyStatus().name(),
                result.cycleEnded(),
                result.cycleEndDate()
        );
    }
}
