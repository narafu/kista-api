package com.kista.domain.model.strategy;

import com.kista.common.TimeZones;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 전략 안의 매매 한 라운드 — 매수 시작에서 holdings=0 청산까지
// 여러 StrategyCycle이 동일 Strategy에 누적됨 (사이클 이력 추적 가능)
// 리버스모드 여부는 공통 strategy_cycle이 아닌 INFINITE 상세 모델이 SSOT
public record StrategyCycle(
        UUID id,                          // PK (null이면 @GeneratedValue)
        UUID strategyId,                  // FK → strategy.id
        BigDecimal startAmount,           // 사이클 시작금액 (USD 시드)
        BigDecimal endAmount,             // 사이클 종료금액 (청산 후 USD, 진행 중이면 null)
        LocalDate startDate,              // 사이클 시작일자 (KST)
        LocalDate endDate,                // 사이클 종료일자 (KST, 진행 중이면 null)
        Instant createdAt,                // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt                 // soft-delete (null=활성)
) {
    public static StrategyCycle start(UUID strategyId, BigDecimal startAmount) {
        return new StrategyCycle(null, strategyId, startAmount, null, LocalDate.now(TimeZones.KST), null, null, null);
    }
}
