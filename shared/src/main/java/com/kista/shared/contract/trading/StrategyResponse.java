package com.kista.shared.contract.trading;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

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
