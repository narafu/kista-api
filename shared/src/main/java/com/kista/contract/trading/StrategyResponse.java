package com.kista.contract.trading;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// 전략 1건 — trading-core 내부 API(/api/internal/trading/accounts/{id}/strategies 등) 응답
public record StrategyResponse(
        UUID id,                            // PK
        UUID accountId,                     // FK → accounts.id
        StrategyType type,                  // 매매 전략 종류
        StrategyStatus status,              // 전략 실행 상태
        StrategyTicker ticker,              // 거래 종목
        StrategyCycleSeedType cycleSeedType // 사이클 종료 후 자동 재등록 정책
) {}
