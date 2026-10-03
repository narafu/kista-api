package com.kista.tradingstats.application.service;

import com.kista.tradingstats.domain.model.backtest.BacktestCommand;
import com.kista.tradingstats.domain.model.backtest.BacktestResult;
import com.kista.tradingstats.domain.model.DailyCandle;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.PrivacyPlan;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.Strategy;
import com.kista.tradingstats.application.port.output.HistoricalCandlePort;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kista.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.sharedkernel.RecurringMode;
import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyFieldSettings;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@ExtendWith(MockitoExtension.class)
class BacktestServiceTest {

    @Mock HistoricalCandlePort candlePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleOrderStrategy planner;
    @Mock StrategyCreationPolicyPort strategyCreationPolicyPort;

    @InjectMocks BacktestService service;

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 5);
    private static final BigDecimal SEED = new BigDecimal("1000");
    // VR 런타임 생성 정책 — bandWidth 10/15/20(15.00과 scale만 다른 값도 허용돼야 함), intervalWeeks 2/4
    private static final StrategyCreationSettings VR_SETTINGS = new StrategyCreationSettings(true,
            new StrategyFieldSettings<>(true, List.of(StrategyTicker.TQQQ), StrategyTicker.TQQQ),
            new StrategyFieldSettings<>(false, List.of(40), 40),
            new StrategyFieldSettings<>(true, List.of(RecurringMode.HOLD, RecurringMode.DEPOSIT, RecurringMode.WITHDRAW), RecurringMode.HOLD),
            new StrategyFieldSettings<>(true, List.of(new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("20")), new BigDecimal("15")),
            new StrategyFieldSettings<>(true, List.of(2, 4), 4));

    @BeforeEach
    void stubVrPolicy() {
        lenient().when(strategyCreationPolicyPort.find(StrategyType.VR)).thenReturn(Optional.of(VR_SETTINGS));
    }

    private static BacktestCommand infinite(Integer divisionCount) {
        return new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ, FROM, TO, SEED,
                divisionCount, null, null, 0, null);
    }

    private static BacktestCommand vr(BigDecimal bandWidth, Integer intervalWeeks, int recurring, String initialValue) {
        return new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ, FROM, TO, SEED,
                null, bandWidth, intervalWeeks, recurring,
                initialValue == null ? null : new BigDecimal(initialValue));
    }

    private static BacktestCommand privacy() {
        return privacy(FROM, TO);
    }

    private static BacktestCommand privacy(LocalDate from, LocalDate to) {
        return new BacktestCommand(StrategyType.PRIVACY, StrategyTicker.SOXL, from, to, SEED,
                null, null, null, 0, null);
    }

    private static BacktestCommand infiniteWithPosition(BigDecimal seed, Integer holdings, BigDecimal avgPrice) {
        return new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ, FROM, TO, seed,
                null, null, null, 0, null, holdings, avgPrice);
    }

    private static BacktestCommand vrWithPosition(BigDecimal seed, int recurring, String initialValue,
                                                   Integer holdings, BigDecimal avgPrice) {
        return new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ, FROM, TO, seed,
                null, new BigDecimal("15"), 4, recurring,
                initialValue == null ? null : new BigDecimal(initialValue), holdings, avgPrice);
    }

    private static DailyCandle candle(int day, String close) {
        return new DailyCandle(LocalDate.of(2024, 1, day), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close));
    }

    // --- 검증 ---

    @Test
    void 전략이_지원하지_않는_종목이면_거부한다() {
        BacktestCommand command = new BacktestCommand(StrategyType.VR, StrategyTicker.SOXL, FROM, TO, SEED,
                null, new BigDecimal("15"), 4, 0, new BigDecimal("1000"));

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOXL");
        verify(candlePort, never()).fetchDailyCandles(anyString(), any(), any());
    }

    @Test
    void 시드가_0이하면_거부한다() {
        BacktestCommand command = new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ, FROM, TO,
                BigDecimal.ZERO, null, null, null, 0, null);

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시드");
    }

    @Test
    void 음수_시드는_보유_포지션이_있어도_거부한다() {
        BacktestCommand command = infiniteWithPosition(new BigDecimal("-1"), 10, new BigDecimal("50"));

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시드");
        verify(candlePort, never()).fetchDailyCandles(anyString(), any(), any());
    }

    @Test
    void 예수금_없이_기존_보유만으로_시작할_수_있다() {
        when(cycleOrderStrategies.of(StrategyType.INFINITE)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        BacktestCommand command = infiniteWithPosition(BigDecimal.ZERO, 10, new BigDecimal("50"));

        assertThatCode(() -> service.run(command)).doesNotThrowAnyException();
    }

    @Test
    void 보유_수량이_있는데_평단가가_없으면_거부한다() {
        BacktestCommand command = infiniteWithPosition(SEED, 10, null);

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("평단가");
        verify(candlePort, never()).fetchDailyCandles(anyString(), any(), any());
    }

    @Test
    void 보유_수량이_음수면_거부한다() {
        BacktestCommand command = infiniteWithPosition(SEED, -1, new BigDecimal("50"));

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("보유 수량");
    }

    @Test
    void 시작일이_종료일보다_늦으면_거부한다() {
        BacktestCommand command = new BacktestCommand(StrategyType.INFINITE, StrategyTicker.TQQQ, TO, FROM, SEED,
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
        when(cycleOrderStrategies.of(StrategyType.INFINITE)).thenReturn(planner);
        when(planner.availableDivisionCounts()).thenReturn(List.of(20, 30, 40));

        assertThatThrownBy(() -> service.run(infinite(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("25");
    }

    @Test
    void VR_인출식_최소자산에_미달하면_거부한다() {
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));
        // required = 30 × 100 × 4 / 4주 = 3000.00 > 시장가 평가금 0 + 시드 1000 (초기 V 1000은 필요자산 비교에 쓰지 않는다)
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 4, -30, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3000.00");
    }

    @Test
    void VR_인출식_최소자산_검증은_보유분_시장가_평가금을_합산한다() {
        when(cycleOrderStrategies.of(StrategyType.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        // required = 3000.00, seed=0이지만 보유 50주 × 첫 캔들 종가 100 = 5000 ≥ 3000 → 통과(평단가 60 기준 취득원가는 무관)
        BacktestCommand command = vrWithPosition(BigDecimal.ZERO, -30, null, 50, new BigDecimal("60"));

        assertThatCode(() -> service.run(command)).doesNotThrowAnyException();
    }

    @Test
    void VR_인출식_최소자산_검증은_초기V_직접입력으로_우회할_수_없다() {
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        // 보유 10주 × 100 = 1000 + 시드 0 < 3000 — 초기 V를 10000으로 부풀려도 필요자산 비교는 시장가 기준
        BacktestCommand command = vrWithPosition(BigDecimal.ZERO, -30, "10000", 10, new BigDecimal("60"));

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3000.00");
    }

    @Test
    void VR_초기V값이_음수면_거부한다() {
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 4, 0, "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값");
    }

    @Test
    void VR_초기V값_미입력이면_보유분_없이_bootstrap으로_시작한다() {
        when(cycleOrderStrategies.of(StrategyType.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        assertThatCode(() -> service.run(vr(new BigDecimal("15"), 4, 0, null))).doesNotThrowAnyException();
        assertThatCode(() -> service.run(vr(new BigDecimal("15"), 4, 0, "0"))).doesNotThrowAnyException();
    }

    @Test
    void VR_허용값_밖의_밴드폭이나_주기는_거부한다() {
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("12"), 4, 0, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("밴드 폭");
        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 3, 0, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리밸런싱 주기");
        verify(candlePort, never()).fetchDailyCandles(anyString(), any(), any());
    }

    @Test
    void VR_정책이_허용하지_않는_입출금_방식은_거부한다() {
        StrategyCreationSettings holdOnly = new StrategyCreationSettings(true,
                VR_SETTINGS.ticker(), VR_SETTINGS.divisionCount(),
                new StrategyFieldSettings<>(false, List.of(RecurringMode.HOLD), RecurringMode.HOLD),
                VR_SETTINGS.bandWidth(), VR_SETTINGS.intervalWeeks());
        when(strategyCreationPolicyPort.find(StrategyType.VR)).thenReturn(Optional.of(holdOnly));

        assertThatThrownBy(() -> service.run(vr(new BigDecimal("15"), 4, 100, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입출금 방식");
    }

    @Test
    void VR_밴드폭은_scale이_달라도_허용값과_같으면_통과한다() {
        when(cycleOrderStrategies.of(StrategyType.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        assertThatCode(() -> service.run(vr(new BigDecimal("15.00"), 4, 0, "1000"))).doesNotThrowAnyException();
    }

    @Test
    void VR_램프_파라미터는_운영_등록과_같은_규칙으로_검증한다() {
        // poolLimitFloor(0.9) > initialPoolLimitRate(0.75 — 거치식 기본값) → 운영 VrRampValidator와 같은 메시지로 거부
        BacktestCommand command = new BacktestCommand(StrategyType.VR, StrategyTicker.TQQQ, FROM, TO, SEED,
                null, new BigDecimal("15"), 4, 0, new BigDecimal("1000"), null, null,
                new BacktestCommand.VrRampInput(null, null, null, null, null, null, null, new BigDecimal("0.9")));

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolLimitFloor");
    }

    // --- 캔들 조달 범위 ---

    @Test
    void INFINITE는_전일종가_확보용_워밍업_프리픽스를_함께_조회한다() {
        when(cycleOrderStrategies.of(StrategyType.INFINITE)).thenReturn(planner);
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
        when(cycleOrderStrategies.of(StrategyType.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        service.run(vr(new BigDecimal("15"), 4, 0, "1000"));

        verify(candlePort).fetchDailyCandles("TQQQ", FROM, TO);
    }

    @Test
    void PRIVACY도_요청_구간_그대로_조회한다() {
        when(cycleOrderStrategies.of(StrategyType.PRIVACY)).thenReturn(planner);
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
        when(cycleOrderStrategies.of(StrategyType.PRIVACY)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(3, "100"), candle(5, "100")));
        // findTodayTrade는 release_date >= 조회일 중 가장 이른 1건 — 어느 날 조회에도 1/3 세션 적용분(적용 거래일 1/4)이 딸려온다
        PrivacyTradeBase base = baseFor(LocalDate.of(2024, 1, 4));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(base));

        BacktestResult result = service.run(privacy());

        assertThat(result.warnings()).anyMatch(w -> w.contains("기준 매매표 데이터가 2024-01-03부터 존재"));
    }

    @Test
    void PRIVACY_적용_거래일이_다른_기준표는_look_ahead_방지로_버린다() {
        when(cycleOrderStrategies.of(StrategyType.PRIVACY)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(3, "100")));
        // 1/3 세션 적용분(적용 거래일 1/4) — 1/1 세션(적용 거래일 1/2) 조회에도 이게 딸려온다
        when(privacyTradePort.findTodayTrade(any()))
                .thenReturn(Optional.of(baseFor(LocalDate.of(2024, 1, 4))));

        service.run(privacy());

        // 엔진에 전달된 맵에 1/1이 들어가면 미래 기준표로 매매하는 셈 — 1/3만 남아야 한다
        ArgumentCaptor<CycleOrderStrategy.PlanContext> ctxCaptor =
                ArgumentCaptor.forClass(CycleOrderStrategy.PlanContext.class);
        verify(planner, org.mockito.Mockito.times(2)).plan(ctxCaptor.capture());
        assertThat(ctxCaptor.getAllValues().get(0).privacy().privacyPlan()).isNull();
        assertThat(ctxCaptor.getAllValues().get(1).privacy().privacyPlan()).isNotNull();
    }

    @Test
    void PRIVACY_월요일_세션도_그날_발행분_기준표를_적용한다() {
        when(cycleOrderStrategies.of(StrategyType.PRIVACY)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        // 캔들 날짜는 US 세션일 — 금(1/5)·월(1/8). 직전 달력일이 일요일이라 월요일엔 "전날 발행분"이 존재하지 않는다
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(5, "100"), candle(8, "100")));
        // 실제 어댑터 재현 — release_date >= (조회일 − 1일) 중 가장 이른 발행분을 적용 거래일(발행일 + 1일)로 변환해 반환
        List<LocalDate> releaseDates = List.of(
                LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 8));
        when(privacyTradePort.findTodayTrade(any())).thenAnswer(invocation -> {
            LocalDate releaseFrom = invocation.getArgument(0, LocalDate.class).minusDays(1);
            return releaseDates.stream()
                    .filter(release -> !release.isBefore(releaseFrom))
                    .findFirst()
                    .map(release -> baseFor(release.plusDays(1)));
        });

        service.run(privacy(LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 8)));

        ArgumentCaptor<CycleOrderStrategy.PlanContext> ctxCaptor =
                ArgumentCaptor.forClass(CycleOrderStrategy.PlanContext.class);
        verify(planner, org.mockito.Mockito.times(2)).plan(ctxCaptor.capture());
        // 금요일 세션 1/5 → 1/5 발행분(적용 거래일 1/6)
        assertThat(appliedBaseTradeDate(ctxCaptor.getAllValues().get(0))).isEqualTo(LocalDate.of(2024, 1, 6));
        // 월요일 세션 1/8 → 1/8 발행분(적용 거래일 1/9). 캔들 날짜로 그대로 조회하면 이 날은 통째로 매매 없음이 된다
        assertThat(appliedBaseTradeDate(ctxCaptor.getAllValues().get(1))).isEqualTo(LocalDate.of(2024, 1, 9));
    }

    // --- 요약 산수 ---

    @Test
    void 요약은_시작끝_두_지점만으로_수익률을_계산한다() {
        when(cycleOrderStrategies.of(StrategyType.INFINITE)).thenReturn(planner);
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
        when(cycleOrderStrategies.of(StrategyType.INFINITE)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        BacktestResult result = service.run(infinite(null));

        assertThat(result.warnings()).anyMatch(w -> w.contains("일봉 고가/저가 터치"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("장 시작/장 마감 접수 시점"));
        assertThat(result.warnings()).noneMatch(w -> w.contains("적립식/인출식"));
    }

    @Test
    void VR_적립식이면_외부_현금흐름_미반영_경고를_덧붙인다() {
        when(cycleOrderStrategies.of(StrategyType.VR)).thenReturn(planner);
        when(planner.plan(any())).thenReturn(Optional.empty());
        when(candlePort.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(List.of(candle(1, "100"), candle(5, "100")));

        BacktestResult result = service.run(vr(new BigDecimal("15"), 4, 100, "1000"));

        assertThat(result.warnings()).anyMatch(w -> w.contains("적립식/인출식"));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // 엔진에 실제로 전달된 기준표의 적용 거래일 — null이면 그날은 매매 없음으로 떨어진 것
    private static LocalDate appliedBaseTradeDate(CycleOrderStrategy.PlanContext ctx) {
        PrivacyPlan plan = ctx.privacy().privacyPlan();
        return plan == null ? null : plan.trades().getFirst().tradeDate();
    }

    // 적용 거래일이 tradeDate인 기준 매매표 (주문 명세는 비워도 tradeDate 판별에는 1건이면 충분)
    private static PrivacyTradeBase baseFor(LocalDate tradeDate) {
        return new PrivacyTradeBase(UUID.randomUUID(), bd("100"), 0, bd("100"),
                List.of(new PrivacyTradeBase.PrivacyTrade(tradeDate, StrategyTicker.SOXL,
                        OrderType.LOC, OrderDirection.BUY, 1, bd("100"))));
    }

    // 지정가 100 매수 1주 — position=null이라 엔진의 캡 재산정 대상에서 제외된다
    private static CycleOrderStrategy.OrderPlan buyOnePlan() {
        PlannedOrder order = PlannedOrder.of(LocalDate.of(2024, 1, 1), StrategyTicker.TQQQ, OrderType.LIMIT,
                OrderDirection.BUY, 1, bd("100"), OrderTiming.AT_OPEN);
        return new CycleOrderStrategy.OrderPlan(null, null, List.of(order));
    }
}
