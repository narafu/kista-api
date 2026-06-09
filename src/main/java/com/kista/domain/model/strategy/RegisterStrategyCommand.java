package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// 전략 등록 인바운드 파라미터
public record RegisterStrategyCommand(
        Strategy.Type type,
        Strategy.Ticker ticker,                      // null이면 전략 기본값
        BigDecimal initialUsdDeposit,                // null 허용 (선택 입력)
        Strategy.CycleSeedType cycleSeedType         // null이면 NONE으로 처리
) {}
