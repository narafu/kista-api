package com.kista.tradingcore.trading.domain.model;


import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 전략 안의 매매 한 라운드 — 매수 시작에서 holdings=0 청산까지
// 여러 StrategyCycle이 동일 Strategy에 누적됨 (사이클 이력 추적 가능)
// 리버스모드 여부는 공통 strategy_cycle이 아닌 INFINITE 상세 모델이 SSOT
public record StrategyCycle(
        UUID id,                          // PK (null이면 @UuidGenerator)
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

    // 거래일 tradeDate 기준 매매 시작 여부 — 시작예정일 당일 거래일은 아직 시작 전(배치·미리보기·VR 재설정 공용)
    public boolean hasStartedBy(LocalDate tradeDate) {
        return tradeDate.isAfter(startDate);
    }

    // 시작예정일 지정 — 시작일은 호출부가 주입한다 (오늘 시작이면 Clock 기준 오늘)
    public static StrategyCycle start(UUID strategyId, UUID strategyVersionId, BigDecimal startAmount, LocalDate startDate) {
        return new StrategyCycle(null, strategyId, strategyVersionId,
                startAmount, null, startDate, null, null, null);
    }
}
