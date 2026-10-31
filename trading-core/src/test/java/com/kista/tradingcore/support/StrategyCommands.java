package com.kista.tradingcore.support;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.creation.VrRampInput;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.trading.domain.model.ReconfigureVrCommand;
import com.kista.tradingcore.trading.domain.model.RegisterStrategyCommand;

import java.math.BigDecimal;
import java.time.LocalDate;

// 테스트 전용 전략 커맨드 빌더 — 지정하지 않은 필드는 미입력(null·0·VrRampInput.NONE)이라 테스트에는 검증 대상 필드만 드러난다
public final class StrategyCommands {

    private StrategyCommands() {
    }

    public static Register register(StrategyType type) {
        return new Register(type);
    }

    public static Register infinite() {
        return register(StrategyType.INFINITE);
    }

    public static Register vr() {
        return register(StrategyType.VR);
    }

    public static Register dca() {
        return register(StrategyType.DCA);
    }

    public static Reconfigure reconfigureVr() {
        return new Reconfigure();
    }

    // RegisterStrategyCommand 빌더
    public static final class Register {
        private final StrategyType type;               // 전략 타입
        private StrategyTicker ticker;                 // 종목 (null=전략 기본값)
        private BigDecimal initialUsdDeposit;          // 초기 예수금
        private StrategyCycleSeedType cycleSeedType;   // 사이클 시드 타입
        private int divisionCount;                     // 분할 수 (0=미입력)
        private BigDecimal initialHoldings;            // 중간 시작 보유 수량
        private BigDecimal initialAvgPrice;            // 중간 시작 평단가
        private Integer intervalWeeks;                 // VR 리밸런싱 주기
        private BigDecimal bandWidth;                  // VR 밴드 폭
        private Integer recurringAmount;               // VR 주기당 추가 예수금
        private VrRampInput vrRamp = VrRampInput.NONE; // VR 램프 입력
        private LocalDate scheduledStartDate;          // 시작예정일
        private BigDecimal initialVrValue;             // VR 초기 V값
        private DcaStrategyConfig dca;                 // DCA 규칙 설정

        private Register(StrategyType type) {
            this.type = type;
        }

        public Register ticker(StrategyTicker v) { ticker = v; return this; }
        public Register deposit(BigDecimal v) { initialUsdDeposit = v; return this; }
        public Register deposit(String v) { return deposit(new BigDecimal(v)); }
        public Register cycleSeedType(StrategyCycleSeedType v) { cycleSeedType = v; return this; }
        public Register divisionCount(int v) { divisionCount = v; return this; }
        public Register holdings(BigDecimal v) { initialHoldings = v; return this; }
        public Register avgPrice(BigDecimal v) { initialAvgPrice = v; return this; }
        public Register intervalWeeks(Integer v) { intervalWeeks = v; return this; }
        public Register bandWidth(BigDecimal v) { bandWidth = v; return this; }
        public Register bandWidth(String v) { return bandWidth(new BigDecimal(v)); }
        public Register recurringAmount(Integer v) { recurringAmount = v; return this; }
        public Register vrRamp(VrRampInput v) { vrRamp = v; return this; }
        public Register scheduledStartDate(LocalDate v) { scheduledStartDate = v; return this; }
        public Register vrValue(BigDecimal v) { initialVrValue = v; return this; }
        public Register vrValue(String v) { return vrValue(new BigDecimal(v)); }
        public Register dcaConfig(DcaStrategyConfig v) { dca = v; return this; }

        public RegisterStrategyCommand build() {
            return new RegisterStrategyCommand(type, ticker, initialUsdDeposit, cycleSeedType, divisionCount,
                    initialHoldings, initialAvgPrice, intervalWeeks, bandWidth, recurringAmount, vrRamp,
                    scheduledStartDate, initialVrValue, dca);
        }
    }

    // ReconfigureVrCommand 빌더
    public static final class Reconfigure {
        private BigDecimal bandWidth;                  // 밴드 폭
        private Integer intervalWeeks;                 // 리밸런싱 주기
        private Integer recurringAmount;               // 주기당 추가 예수금
        private VrRampInput vrRamp = VrRampInput.NONE; // 램프 입력
        private Integer injectShares;                  // 주입 주식 수
        private BigDecimal injectSharePrice;           // 주입 주식 매수단가
        private BigDecimal injectDeposit;              // 주입 예수금
        private Integer withdrawShares;                // 인출 주식 수
        private BigDecimal withdrawDeposit;            // 인출 예수금

        private Reconfigure() {
        }

        public Reconfigure bandWidth(BigDecimal v) { bandWidth = v; return this; }
        public Reconfigure bandWidth(String v) { return bandWidth(new BigDecimal(v)); }
        public Reconfigure intervalWeeks(Integer v) { intervalWeeks = v; return this; }
        public Reconfigure recurringAmount(Integer v) { recurringAmount = v; return this; }
        public Reconfigure vrRamp(VrRampInput v) { vrRamp = v; return this; }
        public Reconfigure injectShares(Integer shares, BigDecimal price) { injectShares = shares; injectSharePrice = price; return this; }
        public Reconfigure injectShares(Integer shares, String price) { return injectShares(shares, price == null ? null : new BigDecimal(price)); }
        public Reconfigure injectDeposit(BigDecimal v) { injectDeposit = v; return this; }
        public Reconfigure injectDeposit(String v) { return injectDeposit(new BigDecimal(v)); }
        public Reconfigure withdrawShares(Integer v) { withdrawShares = v; return this; }
        public Reconfigure withdrawDeposit(BigDecimal v) { withdrawDeposit = v; return this; }
        public Reconfigure withdrawDeposit(String v) { return withdrawDeposit(new BigDecimal(v)); }

        public ReconfigureVrCommand build() {
            return new ReconfigureVrCommand(bandWidth, intervalWeeks, recurringAmount, vrRamp, injectShares,
                    injectSharePrice, injectDeposit, withdrawShares, withdrawDeposit);
        }
    }
}
