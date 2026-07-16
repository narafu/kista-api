package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// 전략 등록 인바운드 파라미터
public record RegisterStrategyCommand(
        Strategy.Type type,
        Strategy.Ticker ticker,                      // null이면 전략 기본값
        BigDecimal initialUsdDeposit,                // null 허용 (선택 입력), VR에서는 예수금(초기 pool)으로 재사용
        Strategy.CycleSeedType cycleSeedType,        // null이면 NONE으로 처리
        int divisionCount,                           // 분할 수 (20/30/40), 0은 미입력 sentinel로 런타임 기본값 적용
        // VR 전략 전용 필드 (비VR 경로는 null)
        BigDecimal initialValue,                     // 주식 평가금 — 초기 V값 (VR 전용)
        Integer intervalWeeks,                       // 리밸런싱 주기 (주 단위, 1 이상, VR 전용)
        BigDecimal bandWidth,                        // 매수·매도 사다리 밴드 폭 (%, VR 전용)
        Integer recurringAmount                      // 주기당 추가 예수금 (USD, 음수=인출, VR 전용)
) {}
