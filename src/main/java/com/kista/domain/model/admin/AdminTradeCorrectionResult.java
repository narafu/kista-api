package com.kista.domain.model.admin;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 수동 체결 보정 결과 요약 — UI에서 최종 포지션/상태 확인용
public record AdminTradeCorrectionResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        int processedCount,
        int finalHoldings,
        BigDecimal finalAvgPrice,
        BigDecimal finalUsdDeposit,
        Strategy.Status strategyStatus,
        boolean cycleEnded,
        LocalDate cycleEndDate
) {
}
