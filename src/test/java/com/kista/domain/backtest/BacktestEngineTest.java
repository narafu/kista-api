package com.kista.domain.backtest;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestPoint;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.VrCycleOrderStrategy;
import com.kista.domain.strategy.VrStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// BacktestEngine VR 경로 — 합성 OHLC 픽스처 기반 결정적 검증 (mock 없음, 기대값은 손계산 상수)
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
        // seed=1000 → poolLimit=500.00, V=0 → needsBootstrap
        // 2일차 bootstrap: 캡가 = 전일종가 100 × 1.05 = 105.00, 수량 = 500/105 내림 = 4주
        // 3일차: LOC은 종가 기준 판정 — 종가 90 ≤ 105 → 4주×90 = 360.00 체결
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 100, 105, 95, 100),
                candle("2024-01-03", 100, 105, 95, 100),
                candle("2024-01-04", 95, 95, 85, 90)
        ), vrCommand("1000", "0", 52, 0));

        assertThat(output.points()).extracting(BacktestPoint::totalAsset)
                .satisfiesExactly(
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 1일차: 주문 없음
                        p -> assertThat(p).isEqualByComparingTo("1000"),  // 2일차: 아직 미체결(주문만 생성)
                        p -> assertThat(p).isEqualByComparingTo("1000")); // 3일차: 예수금 640 + 4주×90
        assertThat(output.tradeCount()).isEqualTo(1);
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
        // recurring=+500 → G=10, poolLimitRate=0.75. V′ = 1000 + 1000/10 + 500 − 158.11 = 1441.89 > 0 → 롤오버 진행
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
        // 적립금은 예수금에도 실제 현금흐름으로 반영된다 (체결 없음 → 총자산 = 예수금)
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("1500");
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
    @DisplayName("VR 외 전략 타입은 아직 지원하지 않아 즉시 실패한다")
    void VR이_아닌_전략은_예외를_던진다() {
        BacktestCommand infinite = new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-31"), bd(1000),
                20, null, null, 0, null);

        assertThatThrownBy(() -> engine.run(List.of(candle("2024-01-02", 100, 105, 95, 100)), infinite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INFINITE");
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
    @DisplayName("알려진 한계: V=0인 채로 보유가 생기면 0달러 매도 사다리가 나와 보유분이 증발한다")
    void V가_0인_상태에서_보유가_생기면_0달러_매도로_청산된다() {
        // upperBand = 0 × (1+밴드) = 0.00 → sellPrice(s)=0.00, FillSimulator는 high ≥ 0을 항상 만족시킨다.
        // 운영에서는 증권사가 0달러 지정가를 거부해 드러나지 않는 경로 — 백테스트 입력 검증(V > 0 요구)이 필요하다는 근거 테스트.
        BacktestEngine.Output output = engine.run(List.of(
                candle("2024-01-02", 100, 105, 95, 100),
                candle("2024-01-03", 100, 105, 95, 100),
                candle("2024-01-04", 100, 105, 95, 100),
                candle("2024-01-05", 100, 105, 95, 100)
        ), vrCommand("1000", "0", 52, 0));

        // 3일차 bootstrap 체결(4주×100=400) 후, 4일차에 0.00 매도가 전량 체결돼 예수금만 600 남는다
        assertThat(output.points().get(2).totalAsset()).isEqualByComparingTo("1000");
        assertThat(output.points().get(3).totalAsset()).isEqualByComparingTo("600");
    }
}
