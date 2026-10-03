package com.kista.tradingstats.domain.model.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.strategy.VrRampParams;

// 백테스트 실행 입력 — 값 유효성 검증(인출식 최소자산 등)은 API 경계 책임이고 이 record는 입력을 그대로 담는다
public record BacktestCommand(
        StrategyType type,        // 백테스트할 전략 종류
        StrategyTicker ticker,    // 거래 종목
        LocalDate from,            // 시뮬레이션 시작일
        LocalDate to,              // 시뮬레이션 종료일
        BigDecimal seed,           // 시작 예수금 (USD)
        Integer divisionCount,     // INFINITE 전용 분할 수 (VR/PRIVACY는 무시)
        BigDecimal vrBandWidth,    // VR 전용 밴드 폭 (% 단위, 예: 15.00)
        Integer vrIntervalWeeks,   // VR 전용 롤오버 주기 (주)
        int vrRecurringAmount,     // VR 전용 주기당 입출금액 (양수=적립, 0=거치, 음수=인출)
        BigDecimal vrInitialValue, // VR 전용 초기 V값 (null/0이면 첫 캔들 종가 × initialHoldings — 운영 등록과 동일 규칙)
        // 중간부터 시작 — 기존 보유 수량·평단가 (세 전략 공통, null/0이면 빈 포지션에서 시작)
        Integer initialHoldings,   // 시뮬레이션 시작 시점 기존 보유 수량
        BigDecimal initialAvgPrice, // 시뮬레이션 시작 시점 기존 평단가 (initialHoldings>0이면 필수)
        VrRampInput vrRampInput     // VR 전용 램프 8파라미터 입력 (null이거나 필드가 null이면 운영 기본값)
) {
    // VR 램프 8파라미터 원본 입력 — 미지정(null) 필드는 vrRamp()에서 recurringAmount별 운영 기본값으로 채운다
    public record VrRampInput(
            Integer initialGradient,         // gradient 초기값
            Integer gGraceWeeks,             // gradient 램프 유예 주수
            Integer gStepWeeks,              // gradient 램프 단계 주기 (0=램프 비활성)
            Integer gMax,                    // gradient 램프 상한
            BigDecimal initialPoolLimitRate, // poolLimitRate 초기값 (0~1)
            Integer pGraceWeeks,             // poolLimitRate 램프 유예 주수
            Integer pStepWeeks,              // poolLimitRate 램프 단계 주기 (0=램프 비활성)
            BigDecimal poolLimitFloor        // poolLimitRate 램프 하한 (0~1)
    ) {
        static final VrRampInput NONE = new VrRampInput(null, null, null, null, null, null, null, null); // 전부 기본값
    }

    // 12개 필드 호출부(테스트 등) 호환용 — 램프 입력 생략 시 운영 기본값
    public BacktestCommand(StrategyType type, StrategyTicker ticker, LocalDate from, LocalDate to, BigDecimal seed,
            Integer divisionCount, BigDecimal vrBandWidth, Integer vrIntervalWeeks, int vrRecurringAmount,
            BigDecimal vrInitialValue, Integer initialHoldings, BigDecimal initialAvgPrice) {
        this(type, ticker, from, to, seed, divisionCount, vrBandWidth, vrIntervalWeeks, vrRecurringAmount,
                vrInitialValue, initialHoldings, initialAvgPrice, null);
    }

    // 기존 10개 필드 호출부(테스트 등) 호환용 — initialHoldings/initialAvgPrice 생략 시 null(빈 포지션에서 시작)
    public BacktestCommand(StrategyType type, StrategyTicker ticker, LocalDate from, LocalDate to, BigDecimal seed,
            Integer divisionCount, BigDecimal vrBandWidth, Integer vrIntervalWeeks, int vrRecurringAmount,
            BigDecimal vrInitialValue) {
        this(type, ticker, from, to, seed, divisionCount, vrBandWidth, vrIntervalWeeks, vrRecurringAmount,
                vrInitialValue, null, null);
    }

    // 운영 전략 등록과 동일한 기본값 표로 정규화한 VR 램프 파라미터 — 서비스 검증과 엔진 시뮬레이션이 같은 값을 쓴다
    public VrRampParams vrRamp() {
        VrRampInput in = vrRampInput != null ? vrRampInput : VrRampInput.NONE;
        return VrRampParams.withDefaults(vrRecurringAmount, in.initialGradient(), in.gGraceWeeks(), in.gStepWeeks(),
                in.gMax(), in.initialPoolLimitRate(), in.pGraceWeeks(), in.pStepWeeks(), in.poolLimitFloor());
    }

    // seed 미입력(null) 시 0 취급 — holdings만으로 시작하는 백테스트 지원을 위한 null-safe 접근자
    public BigDecimal seedOrZero() {
        return seed != null ? seed : BigDecimal.ZERO;
    }
}
