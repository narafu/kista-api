package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 전략 안의 매매 한 라운드 — 매수 시작에서 holdings=0 청산까지
// 여러 StrategyCycle이 동일 Strategy에 누적됨 (사이클 이력 추적 가능)
// 리버스모드 여부는 strategy_cycle이 아닌 cycle_position.is_reverse_mode가 SSOT
public record StrategyCycle(
        UUID id,                          // PK (null이면 @GeneratedValue)
        UUID strategyId,                  // FK → strategy.id
        BigDecimal startAmount,           // 사이클 시작금액 (USD 시드)
        BigDecimal endAmount,             // 사이클 종료금액 (청산 후 USD, 진행 중이면 null)
        LocalDate startDate,              // 사이클 시작일자 (KST)
        LocalDate endDate,                // 사이클 종료일자 (KST, 진행 중이면 null)
        Instant createdAt,                // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt,                // soft-delete (null=활성)
        SeedResolvedBy seedResolvedBy     // 시드 결정 방식 (audit용)
) {
    // 시드 결정 방식 — 운영 audit / 사일런트 PAUSE 원인 추적용
    public enum SeedResolvedBy {
        BROKER_VERIFIED, // 잔고검증 ON: KIS 실잔고 조회 후 결정
        LEDGER_ONLY,     // 잔고검증 OFF: 내부 원장 기준 결정
        USER_INPUT       // 전략 등록/수정 시 사용자 직접 입력
    }

    // 사이클 회전 시 사용 — seedResolvedBy 명시 필수
    public static StrategyCycle start(UUID strategyId, BigDecimal startAmount, SeedResolvedBy seedResolvedBy) {
        return new StrategyCycle(null, strategyId, startAmount, null, LocalDate.now(), null, null, null, seedResolvedBy);
    }

    // 최초 전략 등록 시 — 사용자 직접 입력
    public static StrategyCycle startFromUserInput(UUID strategyId, BigDecimal startAmount) {
        return start(strategyId, startAmount, SeedResolvedBy.USER_INPUT);
    }
}
