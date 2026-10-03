package com.kista.tradingstats.domain.backtest;

import com.kista.tradingstats.domain.model.backtest.BacktestCommand;
import com.kista.tradingstats.domain.model.backtest.BacktestPoint;
import com.kista.tradingstats.domain.model.DailyCandle;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderDirection;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.privacy.domain.model.PrivacyTradeBase.PrivacyTrade;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.matching.domain.strategy.InfiniteStrategy;
import com.kista.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.matching.domain.strategy.PrivacyStrategy;
import com.kista.matching.domain.strategy.ReverseInfiniteStrategy;
import com.kista.matching.domain.strategy.VrCycleOrderStrategy;
import com.kista.matching.domain.strategy.VrStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// BacktestEngine VR·INFINITE 경로 — 합성 OHLC 픽스처 기반 결정적 검증 (mock 없음, 기대값은 손계산 상수)
class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine(
            new CycleOrderStrategies(List.of(new VrCycleOrderStrategy(new VrStrategy()))));

    private static final BigDecimal BAND_WIDTH = new BigDecimal("15");

    // --- 픽스처 헬퍼 ---

    private static DailyCandle candle(String date, double open, double high, double low, double close) {
        return new DailyCandle(LocalDate.parse(date), bd(open), bd(high), bd(low), bd(close));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    // seed·initialValue는 문자열 생성자로 — BigDecimal.valueOf(double)의 소수 자리(예: 300.0)가 경고 문구에 새어나온다
    private static BacktestCommand vrCommand(String seed, String initialValue, int intervalWeeks, int recurringAmount) {
        return new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                null, BAND_WIDTH, intervalWeeks, recurringAmount, new BigDecimal(initialValue));
    }

    @Test
    @DisplayName("look-ahead 방지: 당일 생성한 주문은 당일 캔들로 체결되지 않고 다음 캔들에서만 체결된다")
    void 당일_생성_주문은_다음_캔들에서만_체결된다() {
        // seed=2000 → poolLimit=1000.00, V=1000·밴드15% → lowerBand=850.00
        // 1일차 주문: LIMIT BUY 1주 @850.00 (m=3은 누적 1275.00 > 1000.00이라 제외, 캡 = 1일차 종가 900 × 1.05 = 945 미적용)
        // 1일차 캔들 저가(790)는 850을 이미 터치한다 — 엔진이 당일 체결시키면 1일차 총자산이 곧바로 줄어든다
        // 2일차 시가 860 > 850이라 갭 체결(시가 체결) 없이 지정가 850에 체결된다
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 800, 910, 790, 900),
                candle("2024-01-03", 860, 870, 790, 800)
        ), vrCommand("2000", "1000", 4, 0));

        // 1일차: 체결 없음 — 예수금 2000 그대로
        assertThat(output.points().get(0).totalAsset()).isEqualByComparingTo("2000");
        // 2일차: 1일차 주문이 지정가 850.00에 체결 → 예수금 1150.00 + 1주×종가 800 = 1950.00
        assertThat(output.points().get(1).totalAsset()).isEqualByComparingTo("1950");
        assertThat(output.tradeCount()).isEqualTo(1);
        assertThat(output.cycleCount()).isEqualTo(1);
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    @DisplayName("bootstrap 경로: V=0이면 첫날 LOC 매수가 나와 둘째 날 체결되고, 이후 V=0 구간엔 매도 사다리가 없다")
    void V가_0이면_첫날_bootstrap_LOC_매수가_생성된다() {
        // seed=1000 → poolLimit=750.00(거치식 initialPoolLimitRate=0.75), V=0 → needsBootstrap
        // 1일차 bootstrap: 캡가 = 다음 세션 기준 전일종가(1일차 종가 100) × 1.05 = 105.00, 수량 = 750/105 내림 = 7주
        // 2일차: LOC은 종가 기준 판정 — 종가 100 ≤ 105 → 7주×100 = 700.00 체결 → 현금 300
        // 2일차 총자산(1000)은 체결가·평가가가 둘 다 종가 100이라 매수수량과 무관하게 seed와 같다 — 수량은 3·4일차가 증명한다
        // holdings=7가 되는 순간(2일차 주문생성 단계) V=0이라 사다리 생성이 skip된다(VrStrategy value=0 가드) — 매도 주문 없음, 보유 유지
        // 3일차 = 300 + 7주×90 = 930, 4일차 = 300 + 7주×110 = 1070
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 100, 105, 95, 100),
                candle("2024-01-03", 100, 105, 95, 100),
                candle("2024-01-04", 95, 95, 85, 90),
                candle("2024-01-05", 100, 115, 90, 110)
        ), vrCommand("1000", "0", 52, 0));

        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 1일차: 주문만 생성
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 2일차: 300 + 7주×100
                        p -> assertThat(p).isEqualByComparingTo("930"),   // 3일차: 300 + 7주×90
                        p -> assertThat(p).isEqualByComparingTo("1070")); // 4일차: 매도 사다리 skip → 보유 유지, 300 + 7주×110
        assertThat(output.tradeCount()).isEqualTo(1); // bootstrap 매수 1건뿐 — V=0 구간 매도 사다리 없음
    }

    @Test
    @DisplayName("사다리 경로: 매도 사다리가 고가를 터치하지 못한 날은 미체결, 터치한 날에 체결된다")
    void 매도_사다리는_고가_터치_여부로_체결이_갈린다() {
        // 1일차 LIMIT BUY 1주 @850.00(캡 945 미적용) → 2일차 체결(저가 790, 시가 860이라 지정가 체결) → 예수금 1150.00, 1주 보유
        // 2일차부터 매도 사다리 LIMIT SELL 1주 @1150.00 (upperBand=1150.00 ÷ 1주) — 4일차 시가 1000 < 1150이라 지정가 체결
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 800, 910, 790, 900),
                candle("2024-01-03", 860, 870, 790, 800),
                candle("2024-01-04", 850, 1000, 800, 900),
                candle("2024-01-05", 1000, 1200, 1000, 1100)
        ), vrCommand("2000", "1000", 4, 0));

        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("2000"),  // 1일차
                        p -> assertThat(p).isEqualByComparingTo("1950"),  // 2일차: 매수 체결(1150 + 800)
                        p -> assertThat(p).isEqualByComparingTo("2050"),  // 3일차: 고가 1000 < 1150 미체결(1150 + 900)
                        p -> assertThat(p).isEqualByComparingTo("2300")); // 4일차: 고가 1200 ≥ 1150 체결 → 전액 예수금
        assertThat(output.tradeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("롤오버: 주기가 도래하면 사이클이 늘고 V′ 공식대로 갱신된 밴드로 다음 주문이 생성된다")
    void 롤오버_시_사이클이_증가하고_갱신된_V로_주문이_생성된다() {
        // 1주 주기, 2024-01-01 시작 → 2024-01-08 도래. G=10(거치식), 평가금 0(보유 없음)
        // V′ = 1000 + 2000/10 + 0 + (0−1000)/(2√10) = 1200 − 158.1138830084 = 1041.89
        // → lowerBand = 1041.89 × 0.85 = 885.61 → 2일차 주문은 LIMIT BUY 1주 @885.61
        // 3일차 저가 880 ≤ 885.61 → 체결 → 예수금 2000 − 885.61 = 1114.39, 1주 보유
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 950, 1000, 900, 950),
                candle("2024-01-08", 950, 1000, 900, 950),
                candle("2024-01-09", 900, 950, 880, 900)
        ), vrCommand("2000", "1000", 1, 0));

        assertThat(output.cycleCount()).isEqualTo(2);
        assertThat(output.points().get(1).totalAsset()).isEqualByComparingTo("2000"); // 롤오버 당일은 아직 미체결
        // 1114.39 + 1주 × 종가 900 = 2014.39 — 체결가 885.61이 곧 갱신된 V′(1041.89)의 증거
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("2014.39");
        assertThat(output.tradeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("램프: 입력한 gradient 램프가 롤오버 V′ 계산에 반영된다 (유예 0주·1주마다 G+1)")
    void 램프_파라미터가_롤오버_gradient에_반영된다() {
        // 위 롤오버 테스트와 같은 입력에 gGraceWeeks=0, gStepWeeks=1만 추가 — 01-08은 경과 1주,
        // gradientAt은 유예 경계 주차부터 1단계로 세므로 단계 수 = (1 − 0)/1 + 1 = 2 → G = 10 + 2 = 12
        // (poolLimitRate 램프는 pStepWeeks=0으로 꺼서 gradient 효과만 분리)
        // V′ = 1000 + 2000/12 + 0 + (0−1000)/(2√12) = 1166.6666667 − 144.3375673 = 1022.33
        // → lowerBand = 1022.33 × 0.85 = 868.98 → 3일차 저가 860에 1주 체결 → 2000 − 868.98 + 900 = 2031.02
        // 램프가 무시되면(G=10 고정) 체결가는 885.61이 되어 총자산이 2014.39로 나온다
        BacktestCommand command = new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal("2000"),
                null, BAND_WIDTH, 1, 0, new BigDecimal("1000"), null, null,
                new BacktestCommand.VrRampInput(null, 0, 1, null, null, null, 0, null));

        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 950, 1000, 900, 950),
                candle("2024-01-08", 950, 1000, 900, 950),
                candle("2024-01-09", 900, 950, 860, 900)
        ), command);

        assertThat(output.cycleCount()).isEqualTo(2);
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("2031.02");
    }

    @Test
    @DisplayName("초기 V 미입력 + 보유분이 있으면 첫 캔들 종가 × 보유수량을 V로 쓴다 (운영 등록과 동일 우선순위)")
    void 초기V_미입력이면_보유분_평가금이_V가_된다() {
        BacktestCommand command = new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), BigDecimal.ZERO,
                null, BAND_WIDTH, 4, 0, null, 3, new BigDecimal("80"));

        assertThat(BacktestEngine.initialVrValue(command, new BigDecimal("100.005"))).isEqualByComparingTo("300.02");
        // 직접 입력(>0)이 있으면 그 값이 우선
        BacktestCommand explicit = new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), BigDecimal.ZERO,
                null, BAND_WIDTH, 4, 0, new BigDecimal("500"), 3, new BigDecimal("80"));
        assertThat(BacktestEngine.initialVrValue(explicit, new BigDecimal("100"))).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("휴장 due일: 직전 거래일 종가로 평가하고 그날을 새 사이클 시작일로 잡아 N주 스케줄이 밀리지 않는다")
    void 휴장_due일은_직전_거래일_기준으로_롤오버된다() {
        RecordingVr recorder = new RecordingVr();
        BacktestEngine vrEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 2주 주기, 01-01 시작 → due 01-15(캔들 없음 = 휴장). 01-16에 롤오버하되 평가·시작일은 01-12 캔들 기준
        // 보유 10주 — 평가금 = 10 × 01-12 종가 100 = 1000 (실행일 01-16 종가 110을 쓰면 1100)
        // 사다리(매수 ≤ 85, 매도 ≥ 115)는 종가 100·110 평탄 캔들에 닿지 않아 체결 없음 → pool 2000 유지
        // V′ = 1000 + 2000/10 + (1000 − 1000)/(2√10) = 1200.00 (01-16 종가로 평가하면 1200 + 100/6.3246 = 1215.81)
        // 다음 due = 01-12 + 2주 = 01-26 → 01-26 캔들에서 세 번째 사이클 (실행일 01-16을 시작일로 쓰면 01-30으로 밀린다)
        BacktestCommand command = new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal("2000"),
                null, BAND_WIDTH, 2, 0, new BigDecimal("1000"), 10, new BigDecimal("100"));
        BacktestEngine.Output output = vrEngine.run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-12", 100),
                flat("2024-01-16", 110),
                flat("2024-01-26", 110)
        ), command);

        assertThat(recorder.valueOn("2024-01-16")).isEqualByComparingTo("1200.00");
        assertThat(output.cycleCount()).isEqualTo(3);
        assertThat(output.tradeCount()).isZero();
    }


    @Test
    @DisplayName("V′≤0이면 롤오버를 보류하고 사이클을 유지하며 경고는 보류 구간당 1건만 남긴다")
    void V프라임이_0이하면_롤오버가_보류된다() {
        // V=100, pool=2000, G=40(인출식 기본값), recurring=−1000 → 인출 반영 예수금 1000 ≥ 0이라 인출 보류는 아니고
        // V′ = 100 + 2000/40 − 1000 + (0 − 100)/(2√40) = −857.91 ≤ 0 → V′ 보류 (사다리 매수는 저가 95 > 85라 미체결, 평가금 0)
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 100, 105, 95, 100),
                candle("2024-01-08", 100, 105, 95, 100),
                candle("2024-01-09", 100, 105, 95, 100),
                candle("2024-01-10", 100, 105, 95, 100)
        ), vrCommand("2000", "100", 1, -1000));

        assertThat(output.cycleCount()).isEqualTo(1);
        // 도래일이 3일(01-08·09·10) 이어져도 경고는 1건 — 보류 상태가 풀릴 때까지 중복 기록하지 않는다
        assertThat(output.warnings()).containsExactly("2024-01-08: 다음 주기 목표 평가금(V)이 0 이하로 계산되어 VR 주기 갱신을 보류했습니다.");
        // 보류 시엔 자본 조정도 하지 않는다 — 원금·예수금 불변
        assertThat(output.points()).extracting(BacktestPoint::principal)
                .allSatisfy(p -> assertThat(p).isEqualByComparingTo("2000"));
    }

    @Test
    @DisplayName("적립식: 롤오버 시점에 적립금만큼 원금과 예수금이 함께 증가한다")
    void 적립식은_롤오버_시점에_원금이_증가한다() {
        // recurring=+500 → G=10, poolLimitRate=1.0(적립식 기본값) → poolLimit=1000.00
        // V=1000·밴드15% → lowerBand=850.00 ≤ poolLimit(1000) → 1일차에 사다리 LIMIT BUY 1주 @850.00 생성(bootstrap 아님)
        // 롤오버(01-08): 1주 @850 체결 후 pool 150, 평가금 1×100 → V′ = 1000 + 150/10 + 500 + (100−1000)/(2√10) = 1372.70 > 0 → 롤오버 진행
        // 1일차 종가 900 → 캡 945라 @850 그대로, 2일차 시가 900 > 850이라 지정가 850 체결
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 900, 905, 95, 900),
                candle("2024-01-08", 900, 905, 95, 100),
                candle("2024-01-09", 200, 210, 190, 200)
        ), vrCommand("1000", "1000", 1, 500));

        assertThat(output.cycleCount()).isEqualTo(2);
        assertThat(output.points()).extracting(BacktestPoint::principal)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 1일차
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 2일차 기록은 롤오버 판정 전 시점
                        p -> assertThat(p).isEqualByComparingTo("1500")); // 3일차: 적립 500 반영
        // 2일차: 1주 @850 체결(저가 95가 850 아래라 즉시 체결) → 현금 150 + 1주×종가100 = 250.00
        // 3일차: 현금 150 + 적립 500 = 650 + 1주×종가200 = 850.00
        assertThat(output.points().get(1).totalAsset()).isEqualByComparingTo("250.00");
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("850.00");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    @DisplayName("인출식: 인출 반영 후 예수금이 음수면 V′ 계산 전에 롤오버를 보류한다 (운영 VrCycleRolloverService와 동일)")
    void 인출액이_예수금을_초과하면_롤오버가_보류된다() {
        // seed=300, recurring=−1000 → 300 − 1000 < 0 → 보류(V′ 계산 안 함, 예수금·원금 불변, 경고 1건)
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 100, 105, 95, 100),
                candle("2024-01-08", 100, 105, 95, 100),
                candle("2024-01-09", 100, 105, 95, 100)
        ), vrCommand("300", "5000", 1, -1000));

        assertThat(output.cycleCount()).isEqualTo(1);
        assertThat(output.warnings()).containsExactly("2024-01-08: 인출액이 예수금을 초과해 VR 주기 갱신을 보류했습니다.");
        assertThat(output.points()).extracting(BacktestPoint::principal)
                .allSatisfy(p -> assertThat(p).isEqualByComparingTo("300"));
    }

    @Test
    @DisplayName("PRIVACY를 2-arg 경로로 호출하면 실패한다 — 기준 매매표 맵을 받는 3-arg 오버로드를 쓰라는 신호")
    void 기준매매표_없는_2arg_경로의_PRIVACY는_예외를_던진다() {
        assertThatThrownBy(() -> engine.run(List.of(candle("2024-01-02", 100, 105, 95, 100)), privacyCommand("1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIVACY");
    }

    @Test
    @DisplayName("3-arg 오버로드는 VR·INFINITE 커맨드를 기존 2-arg 경로로 그대로 넘긴다")
    void 오버로드는_비PRIVACY를_기존_경로로_위임한다() {
        List<DailyCandle> candles = List.of(
                candle("2024-01-02", 800, 810, 790, 800),
                candle("2024-01-03", 800, 810, 790, 800));
        BacktestCommand command = vrCommand("2000", "1000", 4, 0);

        // 맵이 비어 있어도 PRIVACY가 아니면 무시된다 — 결과는 2-arg 호출과 완전히 동일해야 한다
        assertThat(engine.run(candles, command, Map.of())).isEqualTo(engine.run(candles, command));
    }

    @Test
    @DisplayName("캔들이 비면 빈 결과를 반환한다")
    void 캔들이_없으면_빈_결과다() {
        BacktestEngine.Output output = engine.run(List.of(), vrCommand("1000", "1000", 4, 0));

        assertThat(output.points()).isEmpty();
        assertThat(output.tradeCount()).isZero();
        assertThat(output.cycleCount()).isZero();
    }

    @Test
    @DisplayName("V=0인 채로 보유가 생겨도 매도 사다리 생성이 skip되어 보유분이 유지된다")
    void V가_0인_상태에서_보유가_생겨도_매도_사다리가_생성되지_않는다() {
        // VrStrategy의 value=0 가드(commit d0056372)로 upperBand=0인 $0 매도 사다리 생성 자체가 막힌다.
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 100, 105, 95, 100),
                candle("2024-01-03", 100, 105, 95, 100),
                candle("2024-01-04", 100, 105, 95, 100),
                candle("2024-01-05", 100, 105, 95, 100)
        ), vrCommand("1000", "0", 52, 0));

        // 1일차 bootstrap LOC 매수 7주가 2일차 종가 100에 체결된 뒤 V=0이라 매도 사다리가 없다 — 3·4일차 = 예수금 300 + 7주×100 = 1000
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("1000");
        assertThat(output.points().get(3).totalAsset()).isEqualByComparingTo("1000");
    }

    // 날짜별 plan() 입력 V값을 붙잡아 두는 VR 기록기 — 롤오버 V′를 주문 가격 역산 없이 직접 단언하기 위함
    private static final class RecordingVr extends VrCycleOrderStrategy {

        private final Map<LocalDate, BigDecimal> valueByDate = new LinkedHashMap<>();

        RecordingVr() {
            super(new VrStrategy());
        }

        @Override
        public Optional<OrderPlan> plan(PlanContext ctx) {
            valueByDate.put(ctx.tradeDate(), ctx.vr().value());
            return super.plan(ctx);
        }

        BigDecimal valueOn(String date) {
            BigDecimal value = valueByDate.get(LocalDate.parse(date));
            assertThat(value).as("%s VR plan() 호출 기록", date).isNotNull();
            return value;
        }
    }

    // --- INFINITE 픽스처 헬퍼 ---

    // 엔진이 조립해 넘긴 PlanContext를 날짜별로 붙잡아 두는 기록기 — mock이 아니라 실제 전략에 그대로 위임한다
    // Output(points/tradeCount)만으론 리버스모드 플래그·별지점 같은 내부 입력을 직접 단언할 수 없어 필요하다
    private static final class RecordingInfinite extends InfiniteCycleOrderStrategy {

        private final Map<LocalDate, Recorded> byDate = new LinkedHashMap<>();

        RecordingInfinite() {
            super(new InfiniteStrategy(), new ReverseInfiniteStrategy());
        }

        @Override
        public Optional<OrderPlan> plan(PlanContext ctx) {
            Optional<OrderPlan> result = super.plan(ctx);
            byDate.put(ctx.tradeDate(),
                    new Recorded(ctx.infinite(), ctx.balance(), result.map(OrderPlan::orders).orElse(List.of())));
            return result;
        }


        Recorded on(String date) {
            Recorded recorded = byDate.get(LocalDate.parse(date));
            assertThat(recorded).as("%s 주문 생성 기록", date).isNotNull();
            return recorded;
        }
    }

    // 하루치 plan() 입력·출력 스냅샷
    private record Recorded(
            CycleOrderStrategy.PlanContext.InfiniteInputs inputs, // 엔진이 조립한 리버스모드·별지점·전일종가
            AccountBalance balance,                               // 체결 반영 후 잔고
            List<PlannedOrder> orders                                    // 캡 보정 전 전략 원본 주문
    ) {
        // 주문 다리 식별자 목록 — 전반/후반/리버스 패턴 판별용
        List<String> legs() {
            return orders.stream().map(PlannedOrder::orderLeg).toList();
        }

        // 전략 계산 시점 포지션 재구성 — currentRound/전후반 판정을 직접 단언하기 위함
        InfinitePosition position(int divisionCount) {
            return new InfinitePosition(balance, StrategyTicker.TQQQ, inputs.prevClosePrice(), divisionCount);
        }
    }

    private static BacktestCommand infiniteCommand(String from, String seed, int divisionCount) {
        return new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ,
                LocalDate.parse(from), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                divisionCount, null, null, 0, null);
    }

    private static BacktestCommand infiniteCommandWithPosition(String from, String seed, int divisionCount,
                                                                int holdings, String avgPrice) {
        return new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ,
                LocalDate.parse(from), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                divisionCount, null, null, 0, null, holdings, new BigDecimal(avgPrice));
    }

    private static DailyCandle flat(String date, double close) {
        return candle(date, close, close, close, close);
    }

    // --- INFINITE 경로 ---

    @Test
    @DisplayName("첫날부터 오늘 종가를 다음 세션 기준 전일종가로 써서 0회차 주문이 나온다 (워밍업 프리픽스 불필요)")
    void 첫날부터_오늘_종가로_0회차_주문을_만든다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // seed=1000, 4분할 → 1일차 종가 100이 다음 세션 주문의 전일종가 — holdings=0이어도 평단가 대용 100으로 계획 가능
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-02", 100),
                flat("2024-01-03", 90),
                candle("2024-01-04", 95, 100, 88, 95)
        ), infiniteCommand("2024-01-01", "1000", 4));

        assertThat(output.warnings()).isEmpty();
        // 1일차: 기준가 100 → unitAmount=1000/4=250.00, 기준가=100×1.15=115.00 → 전반 매수 2건 (115는 접수 전 캡 105로 보정)
        assertThat(recorder.on("2024-01-01").inputs().prevClosePrice()).isEqualByComparingTo("100");
        assertThat(recorder.on("2024-01-01").legs())
                .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY");

        // 2일차 종가 100에 LOC 매수 2건(@100 / 캡 105) 체결 → 예수금 800, 2주
        // 3일차 종가 90에 2일차 LOC 매수 2건(@100 / 109→캡 105) 체결 → 예수금 620, 4주 → 620 + 4×90 = 980
        // 3일차 계획(기준가 90, 캡 94.50): 매수 @95·@98.80은 캡 94.50으로 보정 → 4일차 종가 95 > 94.50 미체결,
        // LOC 매도 @98.81·지정가 매도 @109.25도 미체결 → 620 + 4×95 = 1000
        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("980"),
                        p -> assertThat(p).isEqualByComparingTo("1000"));
        assertThat(output.tradeCount()).isEqualTo(4);
        assertThat(output.cycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주문 기준가(전일종가)는 오늘 종가다 — 오늘 만든 주문은 다음 세션에 체결되므로 운영의 S-1 확정 종가와 같다")
    void 주문_기준가는_오늘_종가다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 종가가 100 → 120으로 바뀌는 날 — 하루 묵은 값(100)을 쓰면 캡·0회차 기준가가 전부 어긋난다
        infiniteEngine.run(List.of(flat("2024-01-01", 100), flat("2024-01-02", 120)),
                infiniteCommand("2024-01-01", "1000", 4));

        assertThat(recorder.on("2024-01-01").inputs().prevClosePrice()).isEqualByComparingTo("100");
        assertThat(recorder.on("2024-01-02").inputs().prevClosePrice()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("initialHoldings/initialAvgPrice로 시작하면 첫날 총자산에 기존 보유분 시장가가 반영된다")
    void 기존_보유분으로_시작하면_첫날_총자산에_반영된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 보유 5주(평단가 80)로 시작
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-02", 100)
        ), infiniteCommandWithPosition("2024-01-02", "0", 4, 5, "80"));

        // 첫날(01-02) 총자산 = 예수금 0 + 보유 5주 × 종가 100 = 500
        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(p -> assertThat(p).isEqualByComparingTo("500"));
        // 원금 = 시드 0 + 취득원가(5주×80) = 400 — 시장가 아닌 실제 투입 비용 기준
        assertThat(output.points().getFirst().principal()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("initialHoldings>0인데 initialAvgPrice가 없으면 NPE 대신 명확한 예외로 거부한다")
    void 보유_수량만_있고_평단가가_없으면_명확히_거부한다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));
        BacktestCommand command = new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ,
                LocalDate.parse("2024-01-02"), LocalDate.parse("2024-12-31"), new BigDecimal("0"),
                4, null, null, 0, null, 5, null);

        assertThatThrownBy(() -> infiniteEngine.run(List.of(flat("2024-01-02", 100)), command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("평단가");
    }

    @Test
    @DisplayName("일반모드: currentRound가 divisionCount/2를 넘으면 전반 2건 매수에서 후반 단일 매수로 패턴이 바뀐다")
    void 전반에서_후반으로_주문_패턴이_전환된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 4분할이라 전후반 경계는 currentRound=2.0 — 종가를 계단식으로 내려 회차를 끌어올린다
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100), flat("2024-01-02", 100), flat("2024-01-03", 90),
                flat("2024-01-04", 85), flat("2024-01-05", 80)
        ), infiniteCommand("2024-01-01", "1000", 4));

        // 3일차: 4주·평단 95.0000·예수금 620 → 매입금 380 ÷ 단위금액 250.00 = 1.52회차 (< 2.0) → 전반
        Recorded early = recorder.on("2024-01-03");
        assertThat(early.position(4).currentRound()).isEqualTo(1.52);
        assertThat(early.position(4).isEarlyStage()).isTrue();
        assertThat(early.legs()).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");

        // 4일차: 캡 보정 매수 2주가 종가 85에 체결 → 6주·평단 91.6667·예수금 450 → 매입금 550 ÷ 250.00 = 2.2회차 (≥ 2.0) → 후반
        Recorded late = recorder.on("2024-01-04");
        assertThat(late.position(4).currentRound()).isEqualTo(2.2);
        assertThat(late.position(4).isEarlyStage()).isFalse();
        assertThat(late.legs()).containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
        // 후반 매수 = 단위금액 250.00 ÷ 기준가 89.83 내림 = 2주 (기준가 = 평단 91.6667 × (1 − 0.02), offset = 0.15×(1−2×2.2/4) = −0.015 → −0.02)
        assertThat(late.orders().getFirst().quantity()).isEqualTo(2);
        assertThat(late.orders().getFirst().price()).isEqualByComparingTo("89.83");

        // 5일차 종가 80에 캡(85×1.05=89.25) 보정 매수 3주 체결 → 예수금 450 − 240 = 210 + 9주×80 = 930
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("930");
        assertThat(output.cycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("리버스모드 진입: 첫날은 MOC 매도만, 둘째 날부터 별지점 기준 LOC 매도·쿼터매수가 나온다")
    void 리버스모드_진입_첫날은_MOC_매도만_생성된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100), flat("2024-01-02", 100), flat("2024-01-03", 90),
                flat("2024-01-04", 85), flat("2024-01-05", 80), flat("2024-01-06", 75),
                flat("2024-01-07", 70), flat("2024-01-08", 65), flat("2024-01-09", 65)
        ), infiniteCommand("2024-01-01", "1000", 4));

        // 7일차는 일반모드 — 8주·예수금 300 ≥ 단위금액 237.22라 아직 최종회차가 아니다
        assertThat(recorder.on("2024-01-07").inputs().isReverseMode()).isFalse();

        // 8일차: 11주·평단 76.7172·예수금 105 → 단위금액 237.22 > 예수금 105 → isFinalRound 성립 → 리버스모드 진입
        Recorded firstDay = recorder.on("2024-01-08");
        assertThat(firstDay.inputs().isReverseMode()).isTrue();
        assertThat(firstDay.inputs().isFirstReverseDay()).isTrue();
        // 진입 첫날은 별지점을 계산하지 않는다(즉시 청산 시작)
        assertThat(firstDay.inputs().starPointPrice()).isNull();
        assertThat(firstDay.legs()).containsExactly("REVERSE_INFINITE_MOC_SELL");
        // MOC 매도 수량 = 11주 ÷ (4분할/2) = 5주
        assertThat(firstDay.orders().getFirst().quantity()).isEqualTo(5);
        assertThat(firstDay.orders().getFirst().orderType()).isEqualTo(OrderType.MOC);

        // 9일차: 별지점 = 최근 5거래일 종가(80·75·70·65·65) 평균 = 355 ÷ 5 = 71.00
        Recorded secondDay = recorder.on("2024-01-09");
        assertThat(secondDay.inputs().isFirstReverseDay()).isFalse();
        assertThat(secondDay.inputs().starPointPrice()).isEqualByComparingTo("71.00");
        assertThat(secondDay.legs()).containsExactly("REVERSE_INFINITE_LOC_SELL", "REVERSE_INFINITE_LOC_BUY");
        // LOC 매도 = 6주 ÷ 2 = 3주 @별지점, 쿼터매수 = (예수금 430 ÷ 4) ÷ 70.99 내림 = 1주 @별지점−0.01
        assertThat(secondDay.orders().get(0).quantity()).isEqualTo(3);
        assertThat(secondDay.orders().get(0).price()).isEqualByComparingTo("71.00");
        assertThat(secondDay.orders().get(1).quantity()).isEqualTo(1);
        assertThat(secondDay.orders().get(1).price()).isEqualByComparingTo("70.99");

        // 8일차 MOC 매도 5주가 9일차 종가 65에 체결 → 예수금 105 + 325 = 430 + 6주×65 = 820.00
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("820");
        assertThat(output.cycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("리버스모드 종료: 종가가 평단×(1−목표수익률) 이상으로 회복되면 일반모드로 복귀한다")
    void 종가가_회복되면_리버스모드가_종료된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 5일차: 9주·평단 87.7778·예수금 210 < 단위금액 250 → 리버스모드 진입(MOC 4주)
        // 6일차: MOC 4주가 종가 75에 체결, 회복 임계선 = 평단 87.7778 × (1 − 0.15) = 74.61 → 75 ≥ 74.61이라 복귀
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100), flat("2024-01-02", 100), flat("2024-01-03", 90),
                flat("2024-01-04", 85), flat("2024-01-05", 80), flat("2024-01-06", 75)
        ), infiniteCommand("2024-01-01", "1000", 4));

        assertThat(recorder.on("2024-01-05").inputs().isReverseMode()).isTrue();

        Recorded back = recorder.on("2024-01-06");
        assertThat(back.inputs().isReverseMode()).isFalse();
        assertThat(back.inputs().starPointPrice()).isNull();
        // 일반모드 주문 다리로 복귀 — 5주·평단 87.7778·예수금 510 → 단위금액 (510 + 438.89)/4 = 237.22, 1.85회차라 전반
        assertThat(back.position(4).currentRound()).isEqualTo(1.85);
        assertThat(back.legs()).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");

        // 예수금 210 + MOC 4주×75 = 510 + 5주×75 = 885.00
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("885");
        assertThat(output.cycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("사이클 종료·재시작: 청산되면 cycleCount가 늘고 리버스모드는 꺼지며 예수금은 그대로 이월된다")
    void 청산되면_새_사이클이_리버스모드_해제_상태로_시작된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 2분할이면 리버스모드 MOC 매도 수량 = holdings ÷ (2/2) = 전량이라 리버스모드 상태 그대로 청산에 도달한다
        // nextReverseMode()는 holdings=0이면 "유지"를 반환하므로, 새 사이클이 일반모드로 시작되는 건 rotateCycle()의 리셋 덕분이다
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100), flat("2024-01-02", 100), flat("2024-01-03", 100),
                flat("2024-01-04", 95), flat("2024-01-05", 90)
        ), infiniteCommand("2024-01-01", "1000", 2));

        // 1일차 매수 5주(@100, 캡 보정분 포함)가 2일차 체결 → 1.0회차(후반) 매수 5주 @100이 3일차 체결
        // 3일차: 10주·평단 100·예수금 0 → 단위금액 500.00 > 0 → 리버스모드 진입, 전량(10주) MOC 매도
        Recorded liquidating = recorder.on("2024-01-03");
        assertThat(liquidating.inputs().isReverseMode()).isTrue();
        assertThat(liquidating.inputs().isFirstReverseDay()).isTrue();
        assertThat(liquidating.orders().getFirst().quantity()).isEqualTo(10);

        // 4일차: 10주가 종가 95에 전량 체결 → holdings 0 → 사이클 종료·즉시 재시작
        Recorded restarted = recorder.on("2024-01-04");
        assertThat(output.cycleCount()).isEqualTo(2);
        assertThat(restarted.balance().holdings()).isZero();
        // 리버스모드·별지점 윈도우 리셋 — 새 사이클은 항상 일반모드 0회차로 시작한다
        assertThat(restarted.inputs().isReverseMode()).isFalse();
        assertThat(restarted.inputs().isFirstReverseDay()).isFalse();
        assertThat(restarted.inputs().starPointPrice()).isNull();
        assertThat(restarted.legs()).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY");

        // 자산은 시드로 리셋되지 않고 그대로 이월된다 — 예수금 0 + 매도대금 950 = 950.00
        assertThat(restarted.balance().usdDeposit()).isEqualByComparingTo("950");
        // 5일차: 새 사이클 매수 5주가 종가 90에 체결 → 예수금 500 + 5주×90 = 950.00
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("950");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    @DisplayName("별지점 사이클 스코프 회귀: 새 사이클의 별지점 평균에 이전 사이클 종가가 섞이지 않는다")
    void 별지점_윈도우는_사이클_경계에서_초기화된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 사이클 A(01-02~01-04)는 1000달러대 고가 구간, 사이클 B(01-04~)는 그보다 낮은 구간 —
        // 두 구간의 종가 수준을 벌려 놔야 윈도우 오염이 평균값 차이로 드러난다
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-02", 1000),
                flat("2024-01-03", 1000),
                candle("2024-01-04", 1100, 1200, 1080, 1160), // 지정가 매도 2주@1150 체결 → 전량 청산 → 사이클 종료
                flat("2024-01-05", 1040),
                candle("2024-01-06", 1045, 1050, 1040, 1045),
                flat("2024-01-07", 990),                      // 사이클 B 리버스모드 진입(첫날)
                flat("2024-01-08", 800)                       // 사이클 B 리버스모드 둘째 날 — 별지점 산출
        ), infiniteCommand("2024-01-02", "3000", 4));

        assertThat(output.cycleCount()).isEqualTo(2);
        // 청산 당일이 곧 사이클 B의 0회차 — 예수금 1000 + 매도대금 2300 = 3300.00이 그대로 이월된다
        assertThat(recorder.on("2024-01-04").balance().holdings()).isZero();
        assertThat(recorder.on("2024-01-04").balance().usdDeposit()).isEqualByComparingTo("3300.00");

        assertThat(recorder.on("2024-01-07").inputs().isFirstReverseDay()).isTrue();

        // 사이클 B의 별지점 = 사이클 B 종가 4개(1040·1045·990·800) 평균 = 3875 ÷ 4 = 968.75
        // rotateCycle()이 recentCloses를 비우지 않으면 윈도우가 [1160·1040·1045·990·800]이 되어 1007.00이 나온다
        // (사이클 A의 청산일 종가 1160이 섞여 별지점이 38.25달러 위로 밀린다)
        Recorded starDay = recorder.on("2024-01-08");
        assertThat(starDay.inputs().isReverseMode()).isTrue();
        assertThat(starDay.inputs().isFirstReverseDay()).isFalse();
        assertThat(starDay.inputs().starPointPrice()).isEqualByComparingTo("968.75");
        assertThat(starDay.inputs().starPointPrice()).isNotEqualByComparingTo("1007.00");
    }

    // --- PRIVACY 픽스처 헬퍼 ---

    // 날짜별 plan() 결과를 붙잡아 두는 기록기 — 캡 보정 전 전략 원본 주문을 그대로 담는다
    // 기록 키는 계획일(캔들 날짜)이고, 기준 매매표 맵 키는 그 표가 적용되는 세션(= 계획일 다음 캔들) 날짜다
    // 기준 매매표가 없는 날도 엔진이 plan()을 호출하므로 "그날 주문이 비었다"까지 직접 단언할 수 있다
    private static final class RecordingPrivacy extends PrivacyCycleOrderStrategy {

        private final Map<LocalDate, List<PlannedOrder>> byDate = new LinkedHashMap<>();

        RecordingPrivacy() {
            super(new PrivacyStrategy());
        }

        @Override
        public Optional<OrderPlan> plan(PlanContext ctx) {
            Optional<OrderPlan> result = super.plan(ctx);
            byDate.put(ctx.tradeDate(), result.map(OrderPlan::orders).orElse(List.of()));
            return result;
        }

        List<PlannedOrder> on(String date) {
            List<PlannedOrder> orders = byDate.get(LocalDate.parse(date));
            assertThat(orders).as("%s plan() 호출 기록", date).isNotNull();
            return orders;
        }
    }

    private static BacktestEngine privacyEngine(RecordingPrivacy recorder) {
        return new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));
    }

    private static BacktestCommand privacyCommand(String seed) {
        return new BacktestCommand(StrategyType.PRIVACY, StrategyTicker.SOXL,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                null, null, null, 0, null);
    }

    private static BacktestCommand privacyCommandWithPosition(String seed, int holdings, String avgPrice) {
        return new BacktestCommand(StrategyType.PRIVACY, StrategyTicker.SOXL,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                null, null, null, 0, null, holdings, new BigDecimal(avgPrice));
    }

    // 기준 매매표 픽스처 — seed ÷ currentCycleStart가 곧 PrivacyStrategy가 산출할 배수(multiple)다
    private static PrivacyTradeBase privacyBase(String currentCycleStart, int holdings, PrivacyTrade... trades) {
        return new PrivacyTradeBase(null, null, holdings, new BigDecimal(currentCycleStart), List.of(trades));
    }

    // 가격은 문자열 생성자로 — 배수·캡 결과가 소수 자리 없이 그대로 드러나게 한다
    private static PrivacyTrade trade(String date, OrderType orderType, OrderDirection direction,
                                      Integer quantity, String price) {
        return new PrivacyTrade(LocalDate.parse(date), StrategyTicker.SOXL, orderType, direction,
                quantity, new BigDecimal(price));
    }

    // --- PRIVACY 경로 ---

    @Test
    @DisplayName("초기 보유 포지션이 있으면 배수 산출 자본에 시작일 종가 평가액이 반영된다")
    void 보유_포지션이_있으면_배수_산출에_반영된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // 예수금 0 + 보유 5주 × 첫날 종가 100 = 자본 500 ÷ currentCycleStart 500 = 배수 1.00 → 기준표 BUY 3주 그대로 유지
        // (보유분 시장가를 빼먹으면 자본이 0이 되어 배수 0.00 → 주문이 통째로 사라진다)
        // 기준표 목표 보유량(5×배수1.00=5)을 현재 보유(5)와 맞춰 보유 보정(diff) 없이 배수 반영만 순수하게 검증한다
        // 01-02 세션 기준표는 그 전날(01-01) 캔들 처리 끝에 계획된다
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-02", 100)
        ), privacyCommandWithPosition("0", 5, "70"), Map.of(LocalDate.parse("2024-01-02"), privacyBase("500", 5,
                trade("2024-01-02", OrderType.LOC, OrderDirection.BUY, 3, "90"))));

        assertThat(recorder.on("2024-01-01"))
                .filteredOn(o -> o.direction() == OrderDirection.BUY)
                .extracting(PlannedOrder::quantity)
                .containsExactly(3);
    }

    @Test
    @DisplayName("기준 매매표가 있는 날만 주문이 생성되고, 없는 날은 주문 없이 지나가며 결측 구간이 요약된다")
    void 기준매매표가_있는_날만_주문이_생성된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // seed 1000 ÷ currentCycleStart 500 = 배수 2.00 → 기준표 BUY 3주가 6주로 스케일
        // 01-02 세션 기준표만 있다 — 01-01 캔들 처리 끝에 계획, 01-02에 체결 판정
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-02", 90),
                flat("2024-01-03", 80),
                flat("2024-01-04", 80)
        ), privacyCommand("1000"), Map.of(LocalDate.parse("2024-01-02"), privacyBase("500", 0,
                trade("2024-01-02", OrderType.LOC, OrderDirection.BUY, 3, "90"))));

        // 01-01 계획: 01-02 기준표 → BUY 6주 @90 (캡 = 01-01 종가 100 × 1.05 = 105 미적용)
        assertThat(recorder.on("2024-01-01")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(6),
                        o -> assertThat(o.price()).isEqualByComparingTo("90"));
        // 01-02·01-03 계획: 다음 세션(01-03·01-04) 기준표 없음 → 주문 자체가 없다 (01-04는 마지막 캔들이라 계획 없음)
        assertThat(recorder.on("2024-01-02")).isEmpty();
        assertThat(recorder.on("2024-01-03")).isEmpty();

        // 01-02 종가 90에 LOC 6주 체결(540) → 예수금 460, 이후엔 신규 주문이 없어 체결도 없다
        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 01-01
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 01-02: 460 + 6주×90
                        p -> assertThat(p).isEqualByComparingTo("940"),   // 01-03: 460 + 6주×80
                        p -> assertThat(p).isEqualByComparingTo("940"));  // 01-04
        assertThat(output.tradeCount()).isEqualTo(1);
        assertThat(output.cycleCount()).isEqualTo(1);
        // 연속 결측 2일은 개별 경고가 아니라 구간 1건으로 요약된다
        assertThat(output.warnings()).containsExactly("2024-01-03 ~ 2024-01-04(총 2일): 기준 매매표가 없어 매매하지 않았습니다.");
    }

    @Test
    @DisplayName("배수 계약 회귀: 시드를 2배로 올리면 같은 기준표에 대해 주문 수량도 정확히 2배가 된다")
    void 시드를_2배로_올리면_주문_수량도_2배가_된다() {
        // base.holdings=0이라 보유 보정(diff)이 0 — 순수하게 multiple = initialUsdDeposit ÷ currentCycleStart만 검증한다
        // currentCycleStart=500 기준: seed 1000 → 배수 2.00 → 3주×2 = 6주 / seed 2000 → 배수 4.00 → 3주×4 = 12주
        Map<LocalDate, PrivacyTradeBase> bases = Map.of(LocalDate.parse("2024-01-02"), privacyBase("500", 0,
                trade("2024-01-02", OrderType.LOC, OrderDirection.BUY, 3, "90")));
        List<DailyCandle> candles = List.of(flat("2024-01-01", 100), flat("2024-01-02", 100));

        RecordingPrivacy single = new RecordingPrivacy();
        privacyEngine(single).run(candles, privacyCommand("1000"), bases);
        RecordingPrivacy doubled = new RecordingPrivacy();
        privacyEngine(doubled).run(candles, privacyCommand("2000"), bases);

        // 01-02 세션 기준표는 01-01 계획에서 쓰인다
        assertThat(single.on("2024-01-01")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(6));
        assertThat(doubled.on("2024-01-01")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(12));
    }

    @Test
    @DisplayName("사이클 종료·재시작: 청산되면 cycleCount가 늘고 배수 기준 자산이 시드가 아닌 청산 시점 예수금으로 갱신된다")
    void 청산되면_배수_기준_자산이_청산_시점_예수금으로_갱신된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // 01-01 계획 BUY 1주 @100(01-02 기준표) → 01-02 종가 100에 체결(예수금 900)
        // → 01-02 계획 잔량 전량 매도(SELL null quantity, 01-03 기준표) → 01-03 종가 60에 체결 → 예수금 960·보유 0
        // → 01-03 계획에서 사이클 종료·재시작, 개장 자산 = 960
        // 01-04 기준표는 currentCycleStart=96 → 올바르면 배수 960/96 = 10.00 → 10주×10 = 100주
        // 시드(1000)로 잘못 리셋하면 배수 1000/96 = 10.41 → 104주가 되어 값이 어긋난다
        // 01-04 종가 70 > 60이라 그 100주 LOC 매수는 체결되지 않는다(예수금 플로어 경고가 섞이지 않게)
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-02", 100),
                flat("2024-01-03", 60),
                flat("2024-01-04", 70)
        ), privacyCommand("1000"), Map.of(
                LocalDate.parse("2024-01-02"), privacyBase("1000", 0,
                        trade("2024-01-02", OrderType.LOC, OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-03"), privacyBase("1000", 0,
                        trade("2024-01-03", OrderType.LOC, OrderDirection.SELL, null, "50")),
                LocalDate.parse("2024-01-04"), privacyBase("96", 0,
                        trade("2024-01-04", OrderType.LOC, OrderDirection.BUY, 10, "60"))));

        // 01-02 계획: 보유 1주 전량을 잔량 매도로 내보낸다
        assertThat(recorder.on("2024-01-02")).singleElement()
                .satisfies(o -> assertThat(o.direction()).isEqualTo(OrderDirection.SELL),
                        o -> assertThat(o.quantity()).isEqualTo(1));

        assertThat(output.cycleCount()).isEqualTo(2);
        // 마지막 자산 = 예수금 960 (보유 0) — 이 값이 곧 새 사이클의 배수 기준이다
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("960");
        assertThat(recorder.on("2024-01-03")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(100));
        assertThat(output.tradeCount()).isEqualTo(2);
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    @DisplayName("가격 캡: cap을 넘는 BUY만 가격이 cap으로 치환되고 수량은 그대로, cap 이하 주문은 원본 그대로다")
    void cap을_넘는_BUY만_가격이_치환되고_수량은_유지된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // 01-04 세션 기준표를 01-03에 계획 — 캡 = 01-03 종가 100 × 1.05 = 105.00 → BUY @200은 105.00으로 치환, BUY @50은 그대로
        // 01-04 저가 40이 두 지정가를 모두 터치, 시가 110은 두 지정가보다 높아 갭 체결 없이 지정가로 체결된다
        // → 치환된 가격이 그대로 현금에 드러난다
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-02", 100),
                flat("2024-01-03", 100),
                candle("2024-01-04", 110, 110, 40, 100)
        ), privacyCommand("1000"), Map.of(LocalDate.parse("2024-01-04"), privacyBase("1000", 0,
                trade("2024-01-04", OrderType.LIMIT, OrderDirection.BUY, 1, "200"),
                trade("2024-01-04", OrderType.LIMIT, OrderDirection.BUY, 1, "50"))));

        // 캡 보정 전 원본 — 배수 1.00이라 수량은 둘 다 1주, 가격은 기준표 그대로(BUY는 고가 우선 정렬)
        assertThat(recorder.on("2024-01-03")).satisfiesExactly(
                o -> assertThat(o.price()).isEqualByComparingTo("200"),
                o -> assertThat(o.price()).isEqualByComparingTo("50"));
        assertThat(recorder.on("2024-01-03")).allSatisfy(o -> assertThat(o.quantity()).isEqualTo(1));

        // 01-04 체결액 = 105.00 + 50 = 155.00 → 예수금 845 + 2주×종가 100 = 1045.00
        // 캡이 적용되지 않았다면 200 + 50 = 250 체결로 750 + 200 = 950.00이 된다(수량이 바뀌면 이 값도 어긋난다)
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("1045.00");
        assertThat(output.tradeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("결측 구간 경고는 결측 일수가 아니라 구간 수에 비례한다")
    void 결측_구간_경고는_구간당_1건으로_요약된다() {
        // 결측 20일 → 기준표 1일 → 결측 20일. 일당 1건이면 40건이지만 구간 요약이면 2건이다
        List<DailyCandle> candles = new ArrayList<>();
        LocalDate start = LocalDate.parse("2024-01-01");
        for (int i = 0; i < 41; i++) candles.add(flat(start.plusDays(i).toString(), 100));

        // 유일한 기준표 날의 주문은 LOC BUY @1 — 종가 100에서는 체결되지 않아 잔고에 영향을 주지 않는다
        BacktestEngine.Output output = privacyEngine(new RecordingPrivacy()).run(candles, privacyCommand("1000"),
                Map.of(LocalDate.parse("2024-01-21"), privacyBase("1000", 0,
                        trade("2024-01-21", OrderType.LOC, OrderDirection.BUY, 1, "1"))));

        // 결측은 세션 날짜 기준 — 첫 캔들(01-01) 세션은 계획일이 없어 집계 대상이 아니다
        assertThat(output.warnings()).containsExactly(
                "2024-01-02 ~ 2024-01-20(총 19일): 기준 매매표가 없어 매매하지 않았습니다.",   // 구간이 끝나는 기준표 수신일에 flush
                "2024-01-22 ~ 2024-02-10(총 20일): 기준 매매표가 없어 매매하지 않았습니다.");  // 마지막까지 이어진 구간은 루프 종료 후 flush
        assertThat(output.tradeCount()).isZero();
    }

    @Test
    @DisplayName("예수금 음수 방지: 연속 3일 플로어 발동이 일별 경고가 아니라 구간당 1건으로 요약된다")
    void 체결_후_예수금이_음수면_0으로_클램프된다() {
        // 배수 1.00 고정, 기준표 목표 보유를 매일 크게 늘려(250→400→550) 보유 보정(diff)이 매일 시드를 넘기게 만든다
        // 마지막 세션(01-05)까지 기준표를 채워 "결측 구간" 경고가 섞이지 않게 한다
        BacktestEngine.Output output = privacyEngine(new RecordingPrivacy()).run(List.of(
                flat("2024-01-02", 100),
                flat("2024-01-03", 100),
                flat("2024-01-04", 100),
                flat("2024-01-05", 100)
        ), privacyCommand("1000"), Map.of(
                LocalDate.parse("2024-01-03"), privacyBase("1000", 250,
                        trade("2024-01-03", OrderType.LOC, OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-04"), privacyBase("1000", 400,
                        trade("2024-01-04", OrderType.LOC, OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-05"), privacyBase("1000", 550,
                        trade("2024-01-05", OrderType.LOC, OrderDirection.BUY, 1, "100"))));

        // 기준표 키는 적용 세션 — 01-02 세션은 첫 캔들이라 계획일이 없다
        // 01-02 계획(01-03 표): diff=250-0=250 → 251주@100=25,100.0 → 01-03 체결, 1000-25100.0=-24100.0 → 0 클램프(플로어 1일차, 최대부족액 24100.0)
        // 01-03 계획(01-04 표): diff=400-251=149 → 150주@100=15,000.0 → 01-04 체결, -15000.0 → 0 클램프(플로어 2일차)
        // 01-04 계획(01-05 표): diff=550-401=149 → 150주@100=15,000.0 → 01-05 체결, -15000.0 → 0 클램프(플로어 3일차)
        // 01-05는 마지막 캔들이라 계획 없음 → 구간이 루프 종료 시점에 1건으로 flush
        assertThat(output.warnings()).containsExactly(
                "2024-01-03 ~ 2024-01-05(총 3일): 체결 후 예수금이 부족해 0으로 조정했습니다. 최대 부족액은 $24,100.00입니다.");
        // 3영업일 연속 플로어가 발동했는데도 경고는 정확히 1건 — 일수에 비례하지 않는다
        assertThat(output.tradeCount()).isEqualTo(3);
    }
}
