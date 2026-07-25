package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// 전략 등록 인바운드 파라미터
public record RegisterStrategyCommand(
        Strategy.Type type,
        Strategy.Ticker ticker,                      // null이면 전략 기본값
        BigDecimal initialUsdDeposit,                // null 허용 (선택 입력), VR에서는 예수금(초기 pool)으로 재사용
        Strategy.CycleSeedType cycleSeedType,        // null이면 NONE으로 처리
        int divisionCount,                           // 분할 수 (20/30/40), 0은 미입력 sentinel로 런타임 기본값 적용
        // 중간부터 시작 — 기존 보유 수량·평단가 (세 전략 공통, null/0이면 빈 포지션에서 시작)
        Integer initialHoldings,                      // 등록 시점 기존 보유 수량
        BigDecimal initialAvgPrice,                   // 등록 시점 기존 평단가 (initialHoldings>0이면 필수)
        // VR 전략 전용 필드 (비VR 경로는 null)
        Integer intervalWeeks,                       // 리밸런싱 주기 (주 단위, 1 이상, VR 전용)
        BigDecimal bandWidth,                         // 매수·매도 사다리 밴드 폭 (%, VR 전용)
        Integer recurringAmount                      // 주기당 추가 예수금 (USD, 음수=인출, VR 전용)
) {}
