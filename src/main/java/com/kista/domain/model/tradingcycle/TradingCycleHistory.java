package com.kista.domain.model.tradingcycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// TradingService.execute() 종료 시 1건 적재
public record TradingCycleHistory(
        UUID id,                // PK (null이면 @GeneratedValue)
        UUID tradingCycleId,    // FK → trading_cycle.id (UUID 간접 참조)
        BigDecimal usdDeposit,  // 통합주문가능금액 (매매 공식 B 계산 기준)
        BigDecimal avgPrice,    // 평균 매입 단가 (보유수량 0이면 null)
        BigDecimal holdings,    // 보유 수량
        Instant createdAt       // 생성 시각 (null이면 DB DEFAULT)
) {}
