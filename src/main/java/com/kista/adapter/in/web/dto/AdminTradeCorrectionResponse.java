package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminTradeCorrectionResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 수동 체결 보정 응답 DTO
public record AdminTradeCorrectionResponse(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        int processedCount,
        int finalHoldings,
        BigDecimal finalAvgPrice,
        BigDecimal finalUsdDeposit,
        String strategyStatus,
        boolean cycleEnded,
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
