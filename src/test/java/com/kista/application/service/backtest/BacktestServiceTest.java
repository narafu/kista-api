package com.kista.application.service.backtest;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestResult;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.HistoricalCandlePort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestServiceTest {

    @Mock HistoricalCandlePort candlePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleOrderStrategy planner;

    @InjectMocks BacktestService service;

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 5);
    private static final BigDecimal SEED = new BigDecimal("1000");

    private static BacktestCommand infinite(Integer divisionCount) {
        return new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, FROM, TO, SEED,
                divisionCount, null, null, 0, null);
    }

    private static BacktestCommand vr(BigDecimal bandWidth, Integer intervalWeeks, int recurring, String initialValue) {
        return new BacktestCommand(Strategy.Type.VR, Strategy.Ticker.TQQQ, FROM, TO, SEED,
                null, bandWidth, intervalWeeks, recurring,
                initialValue == null ? null : new BigDecimal(initialValue));
    }

    private static BacktestCommand privacy() {
        return new BacktestCommand(Strategy.Type.PRIVACY, Strategy.Ticker.SOXL, FROM, TO, SEED,
                null, null, null, 0, null);
    }

    private static DailyCandle candle(int day, String close) {
        return new DailyCandle(LocalDate.of(2024, 1, day), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close));
    }

    // --- 검증 ---

    @Test
    void 전략이_지원하지_않는_종목이면_거부한다() {
        BacktestCommand command = new BacktestCommand(Strategy.Type.VR, Strategy.Ticker.SOXL, FROM, TO, SEED,
                null, new BigDecimal("15"), 4, 0, new BigDecimal("1000"));

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOXL");
        verify(candlePort, never()).fetchDailyCandles(anyString(), any(), any());
    }

    @Test
    void 시드가_0이하면_거부한다() {
        BacktestCommand command = new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, FROM, TO,
                BigDecimal.ZERO, null, null, null, 0, null);

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시드");
    }

    @Test
    void 시작일이_종료일보다_늦으면_거부한다() {
        BacktestCommand command = new BacktestCommand(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, TO, FROM, SEED,
                null, null, null, 0, null);

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종료일");
    }

    @Test
    void VR_밴드폭이_없으면_거부한다() {
        assertThatThrownBy(() -> service.run(vr(null, 4, 0, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("밴드 폭");
    }

    @Test
    void VR_리밸런싱_주기가_없거나_0이하면_거부한다() {
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), null, 0, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리밸런싱 주기");
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 0, 0, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리밸런싱 주기");
    }

    @Test
    void INFINITE_허용되지_않는_분할수는_거부한다() {
        when(cycleOrderStrategies.of(Strategy.Type.INFINITE)).thenReturn(planner);
        when(planner.availableDivisionCounts()).thenReturn(List.of(20, 30, 40));

        assertThatThrownBy(() -> service.run(infinite(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("25");
    }

    @Test
    void VR_인출식_최소자산에_미달하면_거부한다() {
        // required = 30 × 100 × (4주 / 4주) = 3000.00 > 초기V 1000 + 시드 1000
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 4, -30, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3000.00");
    }

    @Test
    void VR_초기V값이_0이하면_거부한다() {
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 4, 0, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값");
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 4, 0, "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값");
    }

    // --- 캔들 조달 범위 ---

    @Test
    void INFINITE는_전일종가_확보용_워밍업_프리픽스를_함께_조회한다() {
        when(cycleOrderStrategies.of(Strategy.Type.INFINITE)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        service.run(infinite(null));

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(candlePort).fetchDailyCandles(anyString(), fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(FROM.minusDays(10));
        assertThat(toCaptor.getValue()).isEqualTo(TO);
    }

    @Test
    void VR은_요청_구간_그대로_조회한다() {
        when(cycleOrderStrategies.of(Strategy.Type.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        service.run(vr(new BigDecimal("15"), 4, 0, "1000"));

        verify(candlePort).fetchDailyCandles("TQQQ", FROM, TO);
    }

    @Test
    void PRIVACY도_요청_구간_그대로_조회한다() {
        when(cycleOrderStrategies.of(Strategy.Type.PRIVACY)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.empty());

        service.run(privacy());

        verify(candlePort).fetchDailyCandles("SOXL", FROM, TO);
    }

    // --- PRIVACY 기준 매매표 ---

    @Test
    void PRIVACY_기준표_시작일_이전_구간은_실측_시작일로_경고한다() {
        when(cycleOrderStrategies.of(Strategy.Type.PRIVACY)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(3, "100"), candle(5, "100")));
        // findTodayTrade는 release_date >= 조회일 중 가장 이른 1건 — 1/1·1/3 조회에도 1/3 적용분이 딸려온다
        PrivacyTradeBase base = baseFor(LocalDate.of(2024, 1, 3));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(base));

        BacktestResult result = service.run(privacy());

        assertThat(result.warnings()).anyMatch(w -> w.contains("기준 매매표 데이터가 2024-01-03부터 존재"));
    }

    @Test
    void PRIVACY_적용_거래일이_다른_기준표는_look_ahead_방지로_버린다() {
        when(cycleOrderStrategies.of(Strategy.Type.PRIVACY)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(3, "100")));
        when(privacyTradePort.findTodayTrade(any()))
                .thenReturn(Optional.of(baseFor(LocalDate.of(2024, 1, 3))));

        service.run(privacy());

        // 엔진에 전달된 맵에 1/1이 들어가면 미래 기준표로 매매하는 셈 — 1/3만 남아야 한다
        ArgumentCaptor<CycleOrderStrategy.PlanContext> ctxCaptor =
                ArgumentCaptor.forClass(CycleOrderStrategy.PlanContext.class);
        verify(planner, org.mockito.Mockito.times(2)).plan(ctxCaptor.capture());
        assertThat(ctxCaptor.getAllValues().get(0).privacy().privacyBase()).isNull();
        assertThat(ctxCaptor.getAllValues().get(1).privacy().privacyBase()).isNotNull();
    }

    // --- 요약 산수 ---

    @Test
    void 요약은_시작끝_두_지점만으로_수익률을_계산한다() {
        when(cycleOrderStrategies.of(Strategy.Type.INFINITE)).thenReturn(planner);
        when(candlePort.fetchDailyCandles(anyString(), any(), any())).thenReturn(List.of(
                new DailyCandle(LocalDate.of(2024, 1, 1), bd("100"), bd("100"), bd("100"), bd("100")),
                new DailyCandle(LocalDate.of(2024, 1, 2), bd("100"), bd("110"), bd("90"), bd("110")),
                new DailyCandle(LocalDate.of(2024, 1, 3), bd("80"), bd("80"), bd("80"), bd("80")),
                new DailyCandle(LocalDate.of(2024, 1, 4), bd("120"), bd("120"), bd("120"), bd("120")),
                new DailyCandle(LocalDate.of(2024, 1, 5), bd("110"), bd("110"), bd("110"), bd("110"))));
        // 1/1은 전일종가가 없어 주문 생략 → 1/2에 계획한 지정가 100 매수 1주가 1/3 저가 80에 체결(예수금 900 + 1주)
        when(planner.plan(any())).thenReturn(Optional.of(buyOnePlan()), Optional.empty());

        BacktestResult result = service.run(infinite(null));

        assertThat(result.points()).extracting(p -> p.totalAsset().toPlainString())
                .containsExactly("1000", "1000", "980", "1020", "1010");
        assertThat(result.summary().finalAsset()).isEqualByComparingTo("1010");
        assertThat(result.summary().totalInvested()).isEqualByComparingTo("1000"); // INFINITE는 외부 현금흐름 없음
        assertThat(result.summary().totalReturnRate()).isEqualByComparingTo("0.01"); // 1000 → 1010
        assertThat(result.summary().mdd()).isEqualByComparingTo("-0.02"); // 고점 1000 → 980
        // 4일간 +1% → 연환산 (1.01^(365/4) − 1)
        assertThat(result.summary().cagr()).isCloseTo(bd("1.4791"), within(bd("0.001")));
        assertThat(result.summary().tradeCount()).isEqualTo(1);
    }

    @Test
    void 거래일이_하루뿐이면_cagr은_null이다() {
        // 캔들이 하루뿐이면 전일종가가 없어 주문 생성 자체가 없다 — 전략 라우터는 호출되지 않는다
        when(candlePort.fetchDailyCandles(anyString(), any(), any())).thenReturn(List.of(candle(1, "100")));

        BacktestResult result = service.run(infinite(null));

        assertThat(result.summary().cagr()).isNull();
        assertThat(result.summary().totalReturnRate()).isEqualByComparingTo("0");
    }

    @Test
    void 항상_체결모델과_주문타이밍_안내를_덧붙인다() {
        when(cycleOrderStrategies.of(Strategy.Type.INFINITE)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        BacktestResult result = service.run(infinite(null));

        assertThat(result.warnings()).anyMatch(w -> w.contains("일봉 고가/저가 터치"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("AT_OPEN/AT_CLOSE"));
        assertThat(result.warnings()).noneMatch(w -> w.contains("적립식/인출식"));
    }

    @Test
    void VR_적립식이면_외부_현금흐름_미반영_경고를_덧붙인다() {
        when(cycleOrderStrategies.of(Strategy.Type.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        BacktestResult result = service.run(vr(new BigDecimal("15"), 4, 100, "1000"));

        assertThat(result.warnings()).anyMatch(w -> w.contains("적립식/인출식"));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // 적용 거래일이 tradeDate인 기준 매매표 (주문 명세는 비워도 tradeDate 판별에는 1건이면 충분)
    private static PrivacyTradeBase baseFor(LocalDate tradeDate) {
        return new PrivacyTradeBase(UUID.randomUUID(), bd("100"), 0, bd("100"),
                List.of(new PrivacyTradeBase.PrivacyTrade(tradeDate, Strategy.Ticker.SOXL,
                        Order.OrderType.LOC, Order.OrderDirection.BUY, 1, bd("100"))));
    }

    // 지정가 100 매수 1주 — position=null이라 엔진의 캡 재산정 대상에서 제외된다
    private static CycleOrderStrategy.OrderPlan buyOnePlan() {
        Order order = new Order(null, UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2024, 1, 1),
                Strategy.Ticker.TQQQ, Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN,
                Order.OrderDirection.BUY, 1, bd("100"), Order.OrderStatus.PLANNED, null, null, null);
        return new CycleOrderStrategy.OrderPlan(null, null, List.of(order));
    }
}
