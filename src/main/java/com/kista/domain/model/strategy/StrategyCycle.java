package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// 전략 안의 매매 한 라운드 — 매수 시작에서 holdings=0 청산까지
// 여러 StrategyCycle이 동일 Strategy에 누적됨 (사이클 이력 추적 가능)
public record StrategyCycle(
        UUID id,                        // PK (null이면 @GeneratedValue)
        UUID strategyId,                // FK → strategy.id
        BigDecimal initialUsdDeposit,   // 이 사이클의 시작 시드(USD)
        Instant createdAt,              // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt               // soft-delete (null=활성)
) {
    // 새 사이클 시작 — id/createdAt은 DB가 생성
    public static StrategyCycle start(UUID strategyId, BigDecimal initialUsdDeposit) {
        return new StrategyCycle(null, strategyId, initialUsdDeposit, null, null);
    }
}
