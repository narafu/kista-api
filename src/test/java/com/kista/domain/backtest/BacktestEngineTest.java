package com.kista.domain.backtest;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestPoint;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeBase.PrivacyTrade;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.InfiniteStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyStrategy;
import com.kista.domain.strategy.ReverseInfiniteStrategy;
import com.kista.domain.strategy.VrCycleOrderStrategy;
import com.kista.domain.strategy.VrStrategy;
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
        return new BacktestCommand(Strategy.Type.VR, Strategy.Ticker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                null, BAND_WIDTH, intervalWeeks, recurringAmount, new BigDecimal(initialValue));
    }

    @Test
    @DisplayName("look-ahead 방지: 당일 생성한 주문은 당일 캔들로 체결되지 않고 다음 캔들에서만 체결된다")
    void 당일_생성_주문은_다음_캔들에서만_체결된다() {
        // seed=2000 → poolLimit=1000.00, V=1000·밴드15% → lowerBand=850.00
        // 1일차 주문: LIMIT BUY 1주 @850.00 (m=3은 누적 1275.00 > 1000.00이라 제외)
        // 1일차 캔들 저가(790)는 850을 이미 터치한다 — 엔진이 당일 체결시키면 1일차 총자산이 곧바로 줄어든다
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 800, 810, 790, 800),
                candle("2024-01-03", 800, 810, 790, 800)
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
    @DisplayName("bootstrap 경로: V=0이면 첫날은 전일종가가 없어 주문이 없고, 둘째 날 LOC 매수가 나와 셋째 날 체결된다")
    void V가_0이면_둘째날_bootstrap_LOC_매수가_생성된다() {
        // seed=1000 → poolLimit=750.00(거치식 initialPoolLimitRate=0.75), V=0 → needsBootstrap
        // 2일차 bootstrap: 캡가 = 전일종가 100 × 1.05 = 105.00, 수량 = 750/105 내림 = 7주
        // 3일차: LOC은 종가 기준 판정 — 종가 90 ≤ 105 → 7주×90 = 630.00 체결
        // 3일차 총자산(1000)은 체결가·평가가가 둘 다 종가 90이라 매수수량과 무관하게 항상 seed와 같다 — 수량 자체는 증명하지 못한다
        // holdings=7가 되는 순간(3일차 주문생성 단계) V=0이라 사다리 생성이 skip된다(VrStrategy value=0 가드) — 매도 주문 없음, 보유 유지
        // 4일차 총자산 = 3일차 체결 직후 현금(1000 − 7주×90 = 370) + 7주 × 4일차 종가(110) = 370 + 770 = 1140
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 100, 105, 95, 100),
                candle("2024-01-03", 100, 105, 95, 100),
                candle("2024-01-04", 95, 95, 85, 90),
                candle("2024-01-05", 100, 115, 90, 110)
        ), vrCommand("1000", "0", 52, 0));

        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 1일차: 주문 없음
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 2일차: 아직 미체결(주문만 생성)
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 3일차: 예수금 370 + 7주×90 (수량과 무관하게 항상 seed와 동일)
                        p -> assertThat(p).isEqualByComparingTo("1140")); // 4일차: 매도 사다리 skip → 보유 유지, 370 + 7주×110
        assertThat(output.tradeCount()).isEqualTo(1); // bootstrap 매수 1건뿐 — V=0 구간 매도 사다리 없음
    }

    @Test
    @DisplayName("사다리 경로: 매도 사다리가 고가를 터치하지 못한 날은 미체결, 터치한 날에 체결된다")
    void 매도_사다리는_고가_터치_여부로_체결이_갈린다() {
        // 1일차 LIMIT BUY 1주 @850.00 → 2일차 체결(저가 790) → 예수금 1150.00, 1주 보유
        // 2일차부터 매도 사다리 LIMIT SELL 1주 @1150.00 (upperBand=1150.00 ÷ 1주)
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 800, 810, 790, 800),
                candle("2024-01-03", 800, 810, 790, 800),
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
    @DisplayName("V′≤0이면 롤오버를 보류하고 사이클을 유지하며 경고는 보류 구간당 1건만 남긴다")
    void V프라임이_0이하면_롤오버가_보류된다() {
        // V=100, pool=100, G=20(인출식), recurring=−1000 → V′ = 100 + 5 − 1000 − 11.18 = −906.18 ≤ 0
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 100, 105, 95, 100),
                candle("2024-01-08", 100, 105, 95, 100),
                candle("2024-01-09", 100, 105, 95, 100),
                candle("2024-01-10", 100, 105, 95, 100)
        ), vrCommand("100", "100", 1, -1000));

        assertThat(output.cycleCount()).isEqualTo(1);
        // 도래일이 3일(01-08·09·10) 이어져도 경고는 1건 — 보류 상태가 풀릴 때까지 중복 기록하지 않는다
        assertThat(output.warnings()).containsExactly("VR 롤오버 보류(V'<=0): date=2024-01-08");
        // 보류 시엔 자본 조정도 하지 않는다 — 원금·예수금 불변
        assertThat(output.points()).extracting(BacktestPoint::principal)
                .allSatisfy(p -> assertThat(p).isEqualByComparingTo("100"));
    }

    @Test
    @DisplayName("적립식: 롤오버 시점에 적립금만큼 원금과 예수금이 함께 증가한다")
    void 적립식은_롤오버_시점에_원금이_증가한다() {
        // recurring=+500 → G=10, poolLimitRate=1.0(적립식 기본값) → poolLimit=1000.00
        // V=1000·밴드15% → lowerBand=850.00 ≤ poolLimit(1000) → 1일차에 사다리 LIMIT BUY 1주 @850.00 생성(bootstrap 아님)
        // V′ = 1000 + 1000/10 + 500 − 158.11 = 1441.89 > 0 → 롤오버 진행
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 100, 105, 95, 100),
                candle("2024-01-08", 100, 105, 95, 100),
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
    @DisplayName("인출식: 인출액이 예수금을 초과하면 예수금은 0에서 멈추고 원금도 실제 차감분만 반영한다")
    void 인출액이_예수금을_초과하면_0으로_클램프된다() {
        // seed=300, V=5000, recurring=−1000 → V′ = 5000 + 15 − 1000 − 559.02 = 3455.98 > 0 → 롤오버 진행
        // 예수금 300 − 1000 = −700 → 0으로 클램프, 원금은 300 − 300 = 0 (요청 인출 1000이 아닌 실제 반영분만)
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-01", 100, 105, 95, 100),
                candle("2024-01-08", 100, 105, 95, 100),
                candle("2024-01-09", 100, 105, 95, 100)
        ), vrCommand("300", "5000", 1, -1000));

        assertThat(output.cycleCount()).isEqualTo(2);
        assertThat(output.warnings()).containsExactly("인출액이 예수금을 초과해 0으로 조정: date=2024-01-08, 부족액=700");
        assertThat(output.points().get(2).principal()).isEqualByComparingTo("0");
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("0");
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

        // 3일차 bootstrap 체결(4주×100=400) 후, 4일차엔 매도 주문이 없어 보유 그대로 평가(4주×종가100=400 + 예수금600)
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("1000");
        assertThat(output.points().get(3).totalAsset()).isEqualByComparingTo("1000");
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

        // 해당 날짜에 plan()이 호출됐는지 — 워밍업 방어로 주문 생성을 건너뛴 날은 false
        boolean planned(String date) {
            return byDate.containsKey(LocalDate.parse(date));
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
            List<Order> orders                                    // 캡 보정 전 전략 원본 주문
    ) {
        // 주문 다리 식별자 목록 — 전반/후반/리버스 패턴 판별용
        List<String> legs() {
            return orders.stream().map(Order::orderLeg).toList();
        }

        // 전략 계산 시점 포지션 재구성 — currentRound/전후반 판정을 직접 단언하기 위함
        InfinitePosition position(int divisionCount) {
            return new InfinitePosition(balance, Strategy.Ticker.TQQQ, inputs.prevClosePrice(), divisionCount);
        }
    }

    private static BacktestCommand infiniteCommand(String from, String seed, int divisionCount) {
        return new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ,
                LocalDate.parse(from), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                divisionCount, null, null, 0, null);
    }

    private static BacktestCommand infiniteCommandWithPosition(String from, String seed, int divisionCount,
                                                                int holdings, String avgPrice) {
        return new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ,
                LocalDate.parse(from), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                divisionCount, null, null, 0, null, holdings, new BigDecimal(avgPrice));
    }

    private static DailyCandle flat(String date, double close) {
        return candle(date, close, close, close, close);
    }

    // --- INFINITE 경로 ---

    @Test
    @DisplayName("워밍업 없이 from부터 캔들이 시작하면 첫날 주문만 생략하고 경고를 남긴 뒤 둘째 날부터 정상 진행된다")
    void 전일종가가_없는_첫날은_주문을_생략하고_경고를_남긴다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // seed=1000, 4분할 → 1일차는 holdings=0·prevClose=null이라 planNormalMode()가 예외를 던질 상황 → 호출 자체를 막는다
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-02", 100),
                flat("2024-01-03", 90),
                candle("2024-01-04", 95, 100, 88, 95)
        ), infiniteCommand("2024-01-01", "1000", 4));

        assertThat(output.warnings()).containsExactly("전일종가 없음, 첫 거래일 주문 생략: date=2024-01-01");
        // 예외를 잡아서 넘기는 게 아니라 전략 호출 자체가 없었어야 한다
        assertThat(recorder.planned("2024-01-01")).isFalse();

        // 2일차: prevClose=100 → 평단가 대용 100, unitAmount=1000/4=250.00, 기준가=100×1.15=115.00 → 전반 매수 2건
        assertThat(recorder.on("2024-01-02").inputs().prevClosePrice()).isEqualByComparingTo("100");
        assertThat(recorder.on("2024-01-02").legs())
                .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY");

        // 3일차 종가 90에 LOC 매수 2건(@100.00 / 캡 105.00) 체결 → 예수금 820, 2주 보유
        // 4일차 종가 95에 기준가 매수 1건(@99.00)만 체결 → 예수금 725 + 3주×95 = 1010.00
        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("1010"));
        assertThat(output.tradeCount()).isEqualTo(3);
        assertThat(output.cycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("initialHoldings/initialAvgPrice로 시작하면 첫날 총자산에 기존 보유분 시장가가 반영된다")
    void 기존_보유분으로_시작하면_첫날_총자산에_반영된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 01-01은 from(01-02) 이전 워밍업 — prevClose만 100으로 이월, 보유 5주(평단가 80)로 시작
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100),
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
        BacktestCommand command = new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ,
                LocalDate.parse("2024-01-02"), LocalDate.parse("2024-12-31"), new BigDecimal("0"),
                4, null, null, 0, null, 5, null);

        assertThatThrownBy(() -> infiniteEngine.run(List.of(flat("2024-01-02", 100)), command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("평단가");
    }

    @Test
    @DisplayName("워밍업 프리픽스가 있으면 from 이전 캔들은 전일종가만 채우고 포인트·주문 없이 지나간다")
    void 워밍업_프리픽스는_전일종가만_채운다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 01-01은 from(01-02) 이전 — 체결·포인트·주문 전부 없이 prevClose만 100으로 이월된다
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100),
                flat("2024-01-02", 100),
                flat("2024-01-03", 90),
                candle("2024-01-04", 95, 100, 88, 95)
        ), infiniteCommand("2024-01-02", "1000", 4));

        // 워밍업 덕에 from 당일부터 정상 주문 — 경고 없음
        assertThat(output.warnings()).isEmpty();
        assertThat(recorder.planned("2024-01-01")).isFalse();
        assertThat(recorder.on("2024-01-02").inputs().prevClosePrice()).isEqualByComparingTo("100");
        assertThat(recorder.on("2024-01-02").legs())
                .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY");

        // 포인트는 정확히 from~to 구간(3일)만 — 워밍업 캔들은 자산 곡선에 등장하지 않는다
        assertThat(output.points()).extracting(BacktestPoint::date)
                .containsExactly(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-03"),
                        LocalDate.parse("2024-01-04"));
        // 시드는 from에 그대로 있고 이후 흐름은 워밍업 없는 케이스의 2~4일차와 동일하다
        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("1000"),
                        p -> assertThat(p).isEqualByComparingTo("1010"));
        assertThat(output.tradeCount()).isEqualTo(3);
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

        // 4일차: 4주·평단 87.5000·예수금 650 → 매입금 350 ÷ 단위금액 250.00 = 1.4회차 (< 2.0) → 전반
        Recorded early = recorder.on("2024-01-04");
        assertThat(early.position(4).currentRound()).isEqualTo(1.4);
        assertThat(early.position(4).isEarlyStage()).isTrue();
        assertThat(early.legs()).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");

        // 5일차: 6주·평단 85.0000·예수금 490 → 매입금 510 ÷ 250.00 = 2.04회차 (≥ 2.0) → 후반 단일 매수
        Recorded late = recorder.on("2024-01-05");
        assertThat(late.position(4).currentRound()).isEqualTo(2.04);
        assertThat(late.position(4).isEarlyStage()).isFalse();
        assertThat(late.legs()).containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
        // 후반 매수 수량 = 단위금액 250.00 ÷ 기준가 85.00 내림 = 2주 (기준가 = 평단 85 × (1 + 0.00))
        assertThat(late.orders().getFirst().quantity()).isEqualTo(2);
        assertThat(late.orders().getFirst().price()).isEqualByComparingTo("85.00");

        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("970");
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
                flat("2024-01-07", 70), flat("2024-01-08", 65)
        ), infiniteCommand("2024-01-01", "1000", 4));

        // 6일차까지는 일반모드 — 단위금액 250.00 ≤ 예수금 340이라 아직 최종회차가 아니다
        assertThat(recorder.on("2024-01-06").inputs().isReverseMode()).isFalse();

        // 7일차: 11주·평단 79.0909·예수금 130 → 단위금액 250.00 > 예수금 130 → isFinalRound 성립 → 리버스모드 진입
        Recorded firstDay = recorder.on("2024-01-07");
        assertThat(firstDay.inputs().isReverseMode()).isTrue();
        assertThat(firstDay.inputs().isFirstReverseDay()).isTrue();
        // 진입 첫날은 별지점을 계산하지 않는다(즉시 청산 시작)
        assertThat(firstDay.inputs().starPointPrice()).isNull();
        assertThat(firstDay.legs()).containsExactly("REVERSE_INFINITE_MOC_SELL");
        // MOC 매도 수량 = 11주 ÷ (4분할/2) = 5주
        assertThat(firstDay.orders().getFirst().quantity()).isEqualTo(5);
        assertThat(firstDay.orders().getFirst().orderType()).isEqualTo(Order.OrderType.MOC);

        // 8일차: 별지점 = 최근 5거래일 종가(85·80·75·70·65) 평균 = 375 ÷ 5 = 75.00
        Recorded secondDay = recorder.on("2024-01-08");
        assertThat(secondDay.inputs().isFirstReverseDay()).isFalse();
        assertThat(secondDay.inputs().starPointPrice()).isEqualByComparingTo("75.00");
        assertThat(secondDay.legs()).containsExactly("REVERSE_INFINITE_LOC_SELL", "REVERSE_INFINITE_LOC_BUY");
        // LOC 매도 = 6주 ÷ 2 = 3주 @별지점, 쿼터매수 = (예수금 455 ÷ 4) ÷ 74.99 내림 = 1주 @별지점−0.01
        assertThat(secondDay.orders().get(0).quantity()).isEqualTo(3);
        assertThat(secondDay.orders().get(0).price()).isEqualByComparingTo("75.00");
        assertThat(secondDay.orders().get(1).quantity()).isEqualTo(1);
        assertThat(secondDay.orders().get(1).price()).isEqualByComparingTo("74.99");

        // 7일차 MOC 매도 5주가 8일차 종가 65에 체결 → 예수금 455 + 6주×65 = 845.00
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("845");
        assertThat(output.cycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("리버스모드 종료: 종가가 평단×(1−목표수익률) 이상으로 회복되면 일반모드로 복귀한다")
    void 종가가_회복되면_리버스모드가_종료된다() {
        RecordingInfinite recorder = new RecordingInfinite();
        BacktestEngine infiniteEngine = new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));

        // 진입 시나리오는 위와 동일하되 8일차 종가만 65 → 68로 올린다
        // 회복 임계선 = 평단 79.0909 × (1 − 0.15) = 67.23 → 68 ≥ 67.23이라 복귀
        BacktestEngine.Output output = infiniteEngine.run(List.of(
                flat("2024-01-01", 100), flat("2024-01-02", 100), flat("2024-01-03", 90),
                flat("2024-01-04", 85), flat("2024-01-05", 80), flat("2024-01-06", 75),
                flat("2024-01-07", 70), flat("2024-01-08", 68)
        ), infiniteCommand("2024-01-01", "1000", 4));

        assertThat(recorder.on("2024-01-07").inputs().isReverseMode()).isTrue();

        Recorded back = recorder.on("2024-01-08");
        assertThat(back.inputs().isReverseMode()).isFalse();
        assertThat(back.inputs().starPointPrice()).isNull();
        // 일반모드 주문 다리로 복귀 — 6주·평단 79.0909·예수금 470 → 2.01회차라 후반
        assertThat(back.legs()).containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
        assertThat(back.orders().getFirst().quantity()).isEqualTo(2);
        assertThat(back.orders().getFirst().price()).isEqualByComparingTo("79.09");

        // MOC 매도 5주가 종가 68에 체결 → 예수금 470 + 6주×68 = 878.00
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("878");
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
        ), infiniteCommand("2024-01-02", "1000", 2));

        // 4일차: 10주·평단 97.5·예수금 25 → 단위금액 500.00 > 25 → 리버스모드 진입, 전량(10주) MOC 매도
        Recorded liquidating = recorder.on("2024-01-04");
        assertThat(liquidating.inputs().isReverseMode()).isTrue();
        assertThat(liquidating.inputs().isFirstReverseDay()).isTrue();
        assertThat(liquidating.orders().getFirst().quantity()).isEqualTo(10);

        // 5일차: 10주가 종가 90에 전량 체결 → holdings 0 → 사이클 종료·즉시 재시작
        Recorded restarted = recorder.on("2024-01-05");
        assertThat(output.cycleCount()).isEqualTo(2);
        assertThat(restarted.balance().holdings()).isZero();
        // 리버스모드·별지점 윈도우 리셋 — 새 사이클은 항상 일반모드 0회차로 시작한다
        assertThat(restarted.inputs().isReverseMode()).isFalse();
        assertThat(restarted.inputs().isFirstReverseDay()).isFalse();
        assertThat(restarted.inputs().starPointPrice()).isNull();
        assertThat(restarted.legs()).containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY");

        // 자산은 시드로 리셋되지 않고 그대로 이월된다 — 예수금 25 + 매도대금 900 = 925.00
        assertThat(restarted.balance().usdDeposit()).isEqualByComparingTo("925");
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("925");
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
                flat("2024-01-01", 1000),
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
    // 기준 매매표가 없는 날도 엔진이 plan()을 호출하므로 "그날 주문이 비었다"까지 직접 단언할 수 있다
    private static final class RecordingPrivacy extends PrivacyCycleOrderStrategy {

        private final Map<LocalDate, List<Order>> byDate = new LinkedHashMap<>();

        RecordingPrivacy() {
            super(new PrivacyStrategy());
        }

        @Override
        public Optional<OrderPlan> plan(PlanContext ctx) {
            Optional<OrderPlan> result = super.plan(ctx);
            byDate.put(ctx.tradeDate(), result.map(OrderPlan::orders).orElse(List.of()));
            return result;
        }

        List<Order> on(String date) {
            List<Order> orders = byDate.get(LocalDate.parse(date));
            assertThat(orders).as("%s plan() 호출 기록", date).isNotNull();
            return orders;
        }
    }

    private static BacktestEngine privacyEngine(RecordingPrivacy recorder) {
        return new BacktestEngine(new CycleOrderStrategies(List.of(recorder)));
    }

    private static BacktestCommand privacyCommand(String seed) {
        return new BacktestCommand(Strategy.Type.PRIVACY, Strategy.Ticker.SOXL,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                null, null, null, 0, null);
    }

    private static BacktestCommand privacyCommandWithPosition(String seed, int holdings, String avgPrice) {
        return new BacktestCommand(Strategy.Type.PRIVACY, Strategy.Ticker.SOXL,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), new BigDecimal(seed),
                null, null, null, 0, null, holdings, new BigDecimal(avgPrice));
    }

    // 기준 매매표 픽스처 — seed ÷ currentCycleStart가 곧 PrivacyStrategy가 산출할 배수(multiple)다
    private static PrivacyTradeBase privacyBase(String currentCycleStart, int holdings, PrivacyTrade... trades) {
        return new PrivacyTradeBase(null, null, holdings, new BigDecimal(currentCycleStart), List.of(trades));
    }

    // 가격은 문자열 생성자로 — 배수·캡 결과가 소수 자리 없이 그대로 드러나게 한다
    private static PrivacyTrade trade(String date, Order.OrderType orderType, Order.OrderDirection direction,
                                      Integer quantity, String price) {
        return new PrivacyTrade(LocalDate.parse(date), Strategy.Ticker.SOXL, orderType, direction,
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
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-02", 100)
        ), privacyCommandWithPosition("0", 5, "70"), Map.of(LocalDate.parse("2024-01-02"), privacyBase("500", 5,
                trade("2024-01-02", Order.OrderType.LOC, Order.OrderDirection.BUY, 3, "90"))));

        assertThat(recorder.on("2024-01-02"))
                .filteredOn(o -> o.direction() == Order.OrderDirection.BUY)
                .extracting(Order::quantity)
                .containsExactly(3);
    }

    @Test
    @DisplayName("기준 매매표가 있는 날만 주문이 생성되고, 없는 날은 주문 없이 지나가며 결측 구간이 요약된다")
    void 기준매매표가_있는_날만_주문이_생성된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // seed 1000 ÷ currentCycleStart 500 = 배수 2.00 → 기준표 BUY 3주가 6주로 스케일
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-02", 100),
                flat("2024-01-03", 90),
                flat("2024-01-04", 80)
        ), privacyCommand("1000"), Map.of(LocalDate.parse("2024-01-02"), privacyBase("500", 0,
                trade("2024-01-02", Order.OrderType.LOC, Order.OrderDirection.BUY, 3, "90"))));

        // 1일차: 기준표 있음 → BUY 6주 @90 (전일종가가 없어 캡은 미적용)
        assertThat(recorder.on("2024-01-02")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(6),
                        o -> assertThat(o.price()).isEqualByComparingTo("90"));
        // 2·3일차: 기준표 없음 → 주문 자체가 없다
        assertThat(recorder.on("2024-01-03")).isEmpty();
        assertThat(recorder.on("2024-01-04")).isEmpty();

        // 2일차 종가 90에 LOC 6주 체결(540) → 예수금 460, 3일차는 신규 주문이 없어 체결도 없다
        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 1일차
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 2일차: 460 + 6주×90
                        p -> assertThat(p).isEqualByComparingTo("940"));  // 3일차: 460 + 6주×80
        assertThat(output.tradeCount()).isEqualTo(1);
        assertThat(output.cycleCount()).isEqualTo(1);
        // 연속 결측 2일은 개별 경고가 아니라 구간 1건으로 요약된다
        assertThat(output.warnings()).containsExactly("기준 매매표 없음: 2024-01-03~2024-01-04, 총 2일");
    }

    @Test
    @DisplayName("배수 계약 회귀: 시드를 2배로 올리면 같은 기준표에 대해 주문 수량도 정확히 2배가 된다")
    void 시드를_2배로_올리면_주문_수량도_2배가_된다() {
        // base.holdings=0이라 보유 보정(diff)이 0 — 순수하게 multiple = initialUsdDeposit ÷ currentCycleStart만 검증한다
        // currentCycleStart=500 기준: seed 1000 → 배수 2.00 → 3주×2 = 6주 / seed 2000 → 배수 4.00 → 3주×4 = 12주
        Map<LocalDate, PrivacyTradeBase> bases = Map.of(LocalDate.parse("2024-01-02"), privacyBase("500", 0,
                trade("2024-01-02", Order.OrderType.LOC, Order.OrderDirection.BUY, 3, "90")));
        List<DailyCandle> candles = List.of(flat("2024-01-02", 100));

        RecordingPrivacy single = new RecordingPrivacy();
        privacyEngine(single).run(candles, privacyCommand("1000"), bases);
        RecordingPrivacy doubled = new RecordingPrivacy();
        privacyEngine(doubled).run(candles, privacyCommand("2000"), bases);

        assertThat(single.on("2024-01-02")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(6));
        assertThat(doubled.on("2024-01-02")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(12));
    }

    @Test
    @DisplayName("사이클 종료·재시작: 청산되면 cycleCount가 늘고 배수 기준 자산이 시드가 아닌 청산 시점 예수금으로 갱신된다")
    void 청산되면_배수_기준_자산이_청산_시점_예수금으로_갱신된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // 1일차 BUY 1주 @100 → 2일차 종가 100에 체결(예수금 900) → 2일차 잔량 전량 매도 주문(SELL null quantity)
        // → 3일차 종가 60에 체결 → 예수금 960·보유 0 → 사이클 종료·재시작, 개장 자산 = 960
        // 3일차 기준표는 currentCycleStart=96 → 올바르면 배수 960/96 = 10.00 → 10주×10 = 100주
        // 시드(1000)로 잘못 리셋하면 배수 1000/96 = 10.41 → 104주가 되어 값이 어긋난다
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-02", 100),
                flat("2024-01-03", 100),
                flat("2024-01-04", 60)
        ), privacyCommand("1000"), Map.of(
                LocalDate.parse("2024-01-02"), privacyBase("1000", 0,
                        trade("2024-01-02", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-03"), privacyBase("1000", 0,
                        trade("2024-01-03", Order.OrderType.LOC, Order.OrderDirection.SELL, null, "50")),
                LocalDate.parse("2024-01-04"), privacyBase("96", 0,
                        trade("2024-01-04", Order.OrderType.LOC, Order.OrderDirection.BUY, 10, "60"))));

        // 2일차: 보유 1주 전량을 잔량 매도로 내보낸다
        assertThat(recorder.on("2024-01-03")).singleElement()
                .satisfies(o -> assertThat(o.direction()).isEqualTo(Order.OrderDirection.SELL),
                        o -> assertThat(o.quantity()).isEqualTo(1));

        assertThat(output.cycleCount()).isEqualTo(2);
        // 3일차 자산 = 예수금 960 (보유 0) — 이 값이 곧 새 사이클의 배수 기준이다
        assertThat(output.points().getLast().totalAsset()).isEqualByComparingTo("960");
        assertThat(recorder.on("2024-01-04")).singleElement()
                .satisfies(o -> assertThat(o.quantity()).isEqualTo(100));
        assertThat(output.tradeCount()).isEqualTo(2);
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    @DisplayName("가격 캡: cap을 넘는 BUY만 가격이 cap으로 치환되고 수량은 그대로, cap 이하 주문은 원본 그대로다")
    void cap을_넘는_BUY만_가격이_치환되고_수량은_유지된다() {
        RecordingPrivacy recorder = new RecordingPrivacy();

        // 2일차 캡 = 전일종가 100 × 1.05 = 105.00 → BUY @200은 105.00으로 치환, BUY @50은 그대로
        // 3일차 저가 40이 두 지정가를 모두 터치 → LIMIT은 지정가로 체결되므로 치환된 가격이 그대로 현금에 드러난다
        BacktestEngine.Output output = privacyEngine(recorder).run(List.of(
                flat("2024-01-02", 100),
                flat("2024-01-03", 100),
                candle("2024-01-04", 100, 110, 40, 100)
        ), privacyCommand("1000"), Map.of(LocalDate.parse("2024-01-03"), privacyBase("1000", 0,
                trade("2024-01-03", Order.OrderType.LIMIT, Order.OrderDirection.BUY, 1, "200"),
                trade("2024-01-03", Order.OrderType.LIMIT, Order.OrderDirection.BUY, 1, "50"))));

        // 캡 보정 전 원본 — 배수 1.00이라 수량은 둘 다 1주, 가격은 기준표 그대로(BUY는 고가 우선 정렬)
        assertThat(recorder.on("2024-01-03")).satisfiesExactly(
                o -> assertThat(o.price()).isEqualByComparingTo("200"),
                o -> assertThat(o.price()).isEqualByComparingTo("50"));
        assertThat(recorder.on("2024-01-03")).allSatisfy(o -> assertThat(o.quantity()).isEqualTo(1));

        // 3일차 체결액 = 105.00 + 50 = 155.00 → 예수금 845 + 2주×종가 100 = 1045.00
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
                        trade("2024-01-21", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, "1"))));

        assertThat(output.warnings()).containsExactly(
                "기준 매매표 없음: 2024-01-01~2024-01-20, 총 20일",   // 구간이 끝나는 기준표 수신일에 flush
                "기준 매매표 없음: 2024-01-22~2024-02-10, 총 20일");  // 마지막까지 이어진 구간은 루프 종료 후 flush
        assertThat(output.tradeCount()).isZero();
    }

    @Test
    @DisplayName("예수금 음수 방지: 연속 3일 플로어 발동이 일별 경고가 아니라 구간당 1건으로 요약된다")
    void 체결_후_예수금이_음수면_0으로_클램프된다() {
        // 배수 1.00 고정, 기준표 목표 보유를 매일 크게 늘려(100→250→400→550) 보유 보정(diff)이 매일 시드를 넘기게 만든다
        // 마지막 날(01-05)도 기준표를 채워 "결측 구간" 경고가 섞이지 않게 한다 — 그날 생성된 주문은 체결 기회가 없어 자연히 버려진다
        BacktestEngine.Output output = privacyEngine(new RecordingPrivacy()).run(List.of(
                flat("2024-01-02", 100),
                flat("2024-01-03", 100),
                flat("2024-01-04", 100),
                flat("2024-01-05", 100)
        ), privacyCommand("1000"), Map.of(
                LocalDate.parse("2024-01-02"), privacyBase("1000", 100,
                        trade("2024-01-02", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-03"), privacyBase("1000", 250,
                        trade("2024-01-03", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-04"), privacyBase("1000", 400,
                        trade("2024-01-04", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, "100")),
                LocalDate.parse("2024-01-05"), privacyBase("1000", 550,
                        trade("2024-01-05", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, "100"))));

        // 01-02 주문: diff=100-0=100 → 101주@100=10,100.0 → 01-03 체결, 예수금 1000-10100.0=-9100.0 → 0 클램프(플로어 1일차)
        // 01-03 주문: diff=250-101=149 → 150주@100=15,000.0 → 01-04 체결, 0-15000.0=-15000.0 → 0 클램프(플로어 2일차, 최대부족액 15000.0)
        // 01-04 주문: diff=400-251=149 → 150주@100=15,000.0 → 01-05 체결, 0-15000.0=-15000.0 → 0 클램프(플로어 3일차)
        // 01-05 주문은 만들어지지만 이후 캔들이 없어 체결·경고 없이 버려짐 → 구간이 루프 종료 시점에 1건으로 flush
        assertThat(output.warnings()).containsExactly(
                "체결 후 예수금 부족으로 0 조정: 2024-01-03~2024-01-05, 총 3일, 최대 부족액=15000.0");
        // 3영업일 연속 플로어가 발동했는데도 경고는 정확히 1건 — 일수에 비례하지 않는다
        assertThat(output.tradeCount()).isEqualTo(3);
    }
}
