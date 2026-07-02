package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminOrderCorrectionResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 주문 보정 응답 DTO
public record AdminOrderCorrectionResponse(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        String mode,
        String originalStatus,
        String resultingStatus,
        String replacementExternalOrderId,
        int finalHoldings,
        BigDecimal finalAvgPrice,
        BigDecimal finalUsdDeposit,
        String strategyStatus,
        boolean cycleEnded,
        LocalDate cycleEndDate
) {
    public static AdminOrderCorrectionResponse from(AdminOrderCorrectionResult result) {
        return new AdminOrderCorrectionResponse(
                result.userId(),
                result.accountId(),
                result.strategyId(),
                result.orderId(),
                result.mode().name(),
                result.originalStatus().name(),
                result.resultingStatus().name(),
                result.replacementExternalOrderId(),
                result.finalHoldings(),
                result.finalAvgPrice(),
                result.finalUsdDeposit(),
                result.strategyStatus().name(),
                result.cycleEnded(),
                result.cycleEndDate()
        );
    }
}
