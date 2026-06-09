package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// 한 StrategyCycle 내의 포지션 스냅샷 — 체결마다 1건 append
// 최신 행 = 현재 보유 포지션
public record CyclePosition(
        UUID id,                  // PK (null이면 @GeneratedValue)
        UUID strategyCycleId,     // FK → strategy_cycle.id
        BigDecimal usdDeposit,    // 통합주문가능금액 (매매 공식 B 계산 기준)
        BigDecimal closingPrice,  // 종가 (PRIVACY 또는 초기 등록 시 null)
        BigDecimal avgPrice,      // 평균 매입 단가 (holdings=0이면 null)
        int holdings,             // 보유 수량
        Instant createdAt,        // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt         // soft-delete (null=활성)
) {
    // 사이클 등록·재등록 시 시작점 포지션 (holdings=0, avgPrice=null)
    // price: 재등록이면 현재가, 최초 등록이면 null
    public static CyclePosition startSnapshot(UUID strategyCycleId, BigDecimal usdDeposit, BigDecimal price) {
        return new CyclePosition(null, strategyCycleId, usdDeposit, price, null, 0, null, null);
    }

    // 매매 완료 후 실포지션 스냅샷
    public static CyclePosition tradeSnapshot(UUID strategyCycleId, AccountBalance balance, BigDecimal closingPrice) {
        return new CyclePosition(null, strategyCycleId,
                balance.usdDeposit(), closingPrice, balance.avgPrice(), balance.holdings(), null, null);
    }
}
