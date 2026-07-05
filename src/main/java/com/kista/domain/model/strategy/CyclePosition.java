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
        BigDecimal closingPrice,  // 종가
        BigDecimal avgPrice,      // 평균 매입 단가 (holdings=0이면 null)
        int holdings,             // 보유 수량
        Instant createdAt,        // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt         // soft-delete (null=활성)
) {
    // 최초 전략 등록 시 시작점 포지션 (holdings=0, avgPrice=null, 종가 없음)
    public static CyclePosition initialSnapshot(UUID strategyCycleId, BigDecimal usdDeposit) {
        return new CyclePosition(null, strategyCycleId, usdDeposit, null, null, 0, null, null);
    }

    // 사이클 재등록 시 시작점 포지션 (holdings=0, avgPrice=null)
    // closingPrice: 종료된 사이클의 장마감 가격
    public static CyclePosition cycleStartSnapshot(UUID strategyCycleId, BigDecimal usdDeposit, BigDecimal closingPrice) {
        return new CyclePosition(null, strategyCycleId, usdDeposit, closingPrice, null, 0, null, null);
    }

    public static CyclePosition tradeSnapshot(UUID strategyCycleId, AccountBalance balance, BigDecimal closingPrice) {
        return new CyclePosition(null, strategyCycleId,
                balance.usdDeposit(), closingPrice, balance.avgPrice(), balance.holdings(), null, null);
    }

    // VR 전략 첫 등록 시 초기 포지션 — 예수금은 pool, 보유/평단은 live 잔고 값
    public static CyclePosition vrInitialSnapshot(UUID strategyCycleId, BigDecimal pool,
                                                   int holdings, BigDecimal avgPrice) {
        return new CyclePosition(null, strategyCycleId, pool, null, avgPrice, holdings, null, null);
    }

    // DB 저장 포지션 → 매매 계산용 잔고 변환 (tradeSnapshot의 역방향)
    public AccountBalance toBalance() {
        return new AccountBalance(holdings, avgPrice, usdDeposit);
    }
}
