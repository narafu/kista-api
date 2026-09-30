package com.kista.contract.trading;

import com.kista.sharedkernel.StrategyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 관리자 수동 체결 보정 결과 요약 — POST /api/internal/trading/trade-corrections 응답
public record TradeCorrectionResponse(
        UUID userId,                    // 대상 사용자
        UUID accountId,                 // 대상 계좌
        UUID strategyId,                // 대상 전략
        int processedCount,             // 처리된 체결 건수
        int finalHoldings,              // 보정 후 보유 수량
        BigDecimal finalAvgPrice,       // 보정 후 평단가
        BigDecimal finalUsdDeposit,     // 보정 후 예수금(USD)
        StrategyStatus strategyStatus,  // 보정 후 전략 상태
        boolean cycleEnded,             // 보정으로 사이클이 종료됐는지
        LocalDate cycleEndDate          // 사이클 종료일 (cycleEnded=false면 null)
) {}
