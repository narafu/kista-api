package com.kista.domain.model.backtest;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

// 백테스트 실행 입력 — 값 유효성 검증(인출식 최소자산 등)은 API 경계 책임이고 이 record는 입력을 그대로 담는다
public record BacktestCommand(
        Strategy.Type type,        // 백테스트할 전략 종류
        Strategy.Ticker ticker,    // 거래 종목
        LocalDate from,            // 시뮬레이션 시작일
        LocalDate to,              // 시뮬레이션 종료일
        BigDecimal seed,           // 시작 예수금 (USD)
        Integer divisionCount,     // INFINITE 전용 분할 수 (VR/PRIVACY는 무시)
        BigDecimal vrBandWidth,    // VR 전용 밴드 폭 (% 단위, 예: 15.00)
        Integer vrIntervalWeeks,   // VR 전용 롤오버 주기 (주)
        int vrRecurringAmount,     // VR 전용 주기당 입출금액 (양수=적립, 0=거치, 음수=인출)
        BigDecimal vrInitialValue, // VR 전용 초기 V값 (null이면 0 취급)
        // 중간부터 시작 — 기존 보유 수량·평단가 (세 전략 공통, null/0이면 빈 포지션에서 시작)
        Integer initialHoldings,   // 시뮬레이션 시작 시점 기존 보유 수량
        BigDecimal initialAvgPrice // 시뮬레이션 시작 시점 기존 평단가 (initialHoldings>0이면 필수)
) {
    // 기존 10개 필드 호출부(테스트 등) 호환용 — initialHoldings/initialAvgPrice 생략 시 null(빈 포지션에서 시작)
    public BacktestCommand(Strategy.Type type, Strategy.Ticker ticker, LocalDate from, LocalDate to, BigDecimal seed,
            Integer divisionCount, BigDecimal vrBandWidth, Integer vrIntervalWeeks, int vrRecurringAmount,
            BigDecimal vrInitialValue) {
        this(type, ticker, from, to, seed, divisionCount, vrBandWidth, vrIntervalWeeks, vrRecurringAmount,
                vrInitialValue, null, null);
    }

    // seed 미입력(null) 시 0 취급 — holdings만으로 시작하는 백테스트 지원을 위한 null-safe 접근자
    public BigDecimal seedOrZero() {
        return seed != null ? seed : BigDecimal.ZERO;
    }
}
