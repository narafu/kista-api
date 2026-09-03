package com.kista.trading.domain.model;

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
        UUID strategyVersionId,           // FK → strategy_version.id
        BigDecimal startAmount,           // 사이클 개장 총자산 (USD)
        BigDecimal endAmount,             // 사이클 종료 총자산 (USD, 진행 중이면 null)
        LocalDate startDate,              // 사이클 시작일자 (KST)
        LocalDate endDate,                // 사이클 종료일자 (KST, 진행 중이면 null)
        Instant createdAt,                // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt                 // soft-delete (null=활성)
) {
    public StrategyCycle(
            UUID id,
            UUID strategyId,
            BigDecimal startAmount,
            BigDecimal endAmount,
            LocalDate startDate,
            LocalDate endDate,
            Instant createdAt,
            Instant deletedAt
    ) {
        this(id, strategyId, null, startAmount, endAmount, startDate, endDate, createdAt, deletedAt);
    }

    // 시작예정일 지정 — 미래 시작예정일 기능 전용, 기존 3-인자 오버로드는 "오늘 시작"으로 위임
    public static StrategyCycle start(UUID strategyId, UUID strategyVersionId, BigDecimal startAmount, LocalDate startDate) {
        return new StrategyCycle(null, strategyId, strategyVersionId,
                startAmount, null, startDate, null, null, null);
    }

    public static StrategyCycle start(UUID strategyId, UUID strategyVersionId, BigDecimal startAmount) {
        return start(strategyId, strategyVersionId, startAmount, LocalDate.now(TimeZones.KST));
    }

    public static StrategyCycle start(UUID strategyId, BigDecimal startAmount) {
        return start(strategyId, null, startAmount);
    }
}
