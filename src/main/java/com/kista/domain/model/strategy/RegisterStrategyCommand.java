package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

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
        Integer recurringAmount,                      // 주기당 추가 예수금 (USD, 음수=인출, VR 전용)
        // VR 램프 파라미터 (미지정 시 서비스에서 정규화된 기본값 적용, VR 전용)
        Integer initialGradient,                      // 램프 시작 시점(경과 0주)의 gradient(G) 값
        Integer gGraceWeeks,                          // gradient 램프 시작 전 유예 주수
        Integer gStepWeeks,                           // gradient가 한 단계 상승하는 주기 (주 단위)
        Integer gMax,                                 // gradient 램프의 상한값
        BigDecimal initialPoolLimitRate,               // 램프 시작 시점(경과 0주)의 poolLimitRate 값
        Integer pGraceWeeks,                          // poolLimitRate 램프 시작 전 유예 주수
        Integer pStepWeeks,                           // poolLimitRate가 한 단계 하강하는 주기 (주 단위)
        BigDecimal poolLimitFloor,                     // poolLimitRate 램프의 하한값
        LocalDate scheduledStartDate,                  // 시작예정일 (null이면 오늘, 과거 거부)
        // VR: 초기 V값 직접 지정 (VR 전용, null/0 이하면 미지정 취급 — 평가금 기준으로 대체)
        BigDecimal initialVrValue
) {
    // 기존 19개 필드 호출부(테스트 등) 호환용 — initialVrValue 생략 시 null(미지정)
    public RegisterStrategyCommand(Strategy.Type type, Strategy.Ticker ticker, BigDecimal initialUsdDeposit,
            Strategy.CycleSeedType cycleSeedType, int divisionCount, Integer initialHoldings, BigDecimal initialAvgPrice,
            Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount, Integer initialGradient,
            Integer gGraceWeeks, Integer gStepWeeks, Integer gMax, BigDecimal initialPoolLimitRate,
            Integer pGraceWeeks, Integer pStepWeeks, BigDecimal poolLimitFloor, LocalDate scheduledStartDate) {
        this(type, ticker, initialUsdDeposit, cycleSeedType, divisionCount, initialHoldings, initialAvgPrice,
                intervalWeeks, bandWidth, recurringAmount, initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor, scheduledStartDate, null);
    }
}
