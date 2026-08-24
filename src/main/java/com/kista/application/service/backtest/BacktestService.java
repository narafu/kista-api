package com.kista.application.service.backtest;

import com.kista.domain.backtest.BacktestEngine;
import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestPoint;
import com.kista.domain.model.backtest.BacktestResult;
import com.kista.domain.model.backtest.BacktestSummary;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.stats.ReturnMetrics;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.BacktestUseCase;
import com.kista.domain.port.out.HistoricalCandlePort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
class BacktestService implements BacktestUseCase {

    // INFINITE는 holdings==0일 때 전일종가가 없으면 첫날 주문 자체를 못 만든다 — 미국 최장 연휴+주말도 덮는 워밍업 여유일
    private static final int INFINITE_WARMUP_DAYS = 10;
    // 인출식 최소자산 계수 — StrategyService.validateVrCommand()와 동일한 식(백테스트라고 완화하지 않는다)
    private static final BigDecimal WITHDRAW_MIN_ASSET_MULTIPLIER = new BigDecimal("400"); // 100 × 4주
    private static final String FILL_MODEL_WARNING =
            "체결은 일봉 고가/저가 터치 기준으로 판정됩니다 — 매도 주문은 실제보다 낙관적으로(항상 전량 체결 가정), "
                    + "매수 주문은 가격 캡에 걸릴 경우 실제보다 비관적으로(캡 지정가 그대로 체결 가정) 평가될 수 있습니다.";
    private static final String ORDER_TIMING_WARNING =
            "AT_OPEN/AT_CLOSE 접수 시점 구분은 일봉 해상도에서 반영되지 않습니다.";
    private static final String VR_CASH_FLOW_WARNING =
            "적립식/인출식 설정 시 수익률 지표(totalReturnRate/cagr/mdd)가 외부 현금흐름을 반영하지 않아 "
                    + "실제보다 낙관적/비관적일 수 있습니다.";

    private final HistoricalCandlePort candlePort;         // 과거 일봉 조달 (Alpaca)
    private final PrivacyTradePort privacyTradePort;       // PRIVACY 기준 매매표 조회
    private final CycleOrderStrategies cycleOrderStrategies; // 전략 capability 라우터 (엔진에 그대로 위임)

    @Override
    public BacktestResult run(BacktestCommand command) {
        validate(command);

        List<DailyCandle> candles = fetchCandles(command); // 비어 있으면 어댑터가 이미 IllegalArgumentException
        List<String> warnings = new ArrayList<>();

        // PRIVACY만 날짜별 기준 매매표를 미리 조달한다 — 도메인 엔진은 DB I/O를 할 수 없다
        Map<LocalDate, PrivacyTradeBase> privacyBases = Map.of();
        if (command.type() == Strategy.Type.PRIVACY) {
            privacyBases = loadPrivacyBases(candles);
            addRangeClampWarning(candles, privacyBases, warnings);
        }

        BacktestEngine engine = new BacktestEngine(cycleOrderStrategies); // 무상태 순수 클래스 — Spring 빈 아님
        BacktestEngine.Output output = command.type() == Strategy.Type.PRIVACY
                ? engine.run(candles, command, privacyBases)
                : engine.run(candles, command);

        warnings.addAll(0, output.warnings()); // 엔진 경고를 앞, 항상 붙는 안내를 뒤로
        warnings.add(FILL_MODEL_WARNING);
        warnings.add(ORDER_TIMING_WARNING);
        if (command.type() == Strategy.Type.VR && command.vrRecurringAmount() != 0) warnings.add(VR_CASH_FLOW_WARNING);

        return new BacktestResult(output.points(), summarize(output), List.copyOf(warnings));
    }

    // --- 검증 (전부 IllegalArgumentException → GlobalExceptionHandler 400) ---

    private void validate(BacktestCommand command) {
        if (!command.type().availableTickers().contains(command.ticker())) {
            throw new IllegalArgumentException(
                    command.type() + " 전략이 지원하지 않는 종목입니다: " + command.ticker());
        }
        if (command.seed() == null || command.seed().signum() <= 0) {
            throw new IllegalArgumentException("시드(seed)는 0보다 커야 합니다");
        }
        if (command.from().isAfter(command.to())) {
            throw new IllegalArgumentException("시작일(from)이 종료일(to)보다 늦을 수 없습니다");
        }
        if (command.type() == Strategy.Type.INFINITE && command.divisionCount() != null) {
            List<Integer> allowed = cycleOrderStrategies.of(Strategy.Type.INFINITE).availableDivisionCounts();
            if (!allowed.contains(command.divisionCount())) {
                throw new IllegalArgumentException("지원하지 않는 분할 수(divisionCount)입니다: " + command.divisionCount()
                        + ", 허용값=" + allowed);
            }
        }
        if (command.type() == Strategy.Type.VR) validateVr(command);
    }

    private void validateVr(BacktestCommand command) {
        if (command.vrBandWidth() == null || command.vrBandWidth().signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 밴드 폭(vrBandWidth)은 0보다 커야 합니다");
        }
        if (command.vrIntervalWeeks() == null || command.vrIntervalWeeks() <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(vrIntervalWeeks)는 1 이상이어야 합니다");
        }
        BigDecimal initialValue = command.vrInitialValue() != null ? command.vrInitialValue() : BigDecimal.ZERO;
        // 인출식 최소자산 — 운영 등록 검증(StrategyService)과 동일한 식, BigDecimal 나눗셈이라 정수 절삭 없음
        if (command.vrRecurringAmount() < 0) {
            BigDecimal required = BigDecimal.valueOf(Math.abs((long) command.vrRecurringAmount()))
                    .multiply(WITHDRAW_MIN_ASSET_MULTIPLIER)
                    .divide(BigDecimal.valueOf(command.vrIntervalWeeks()), 2, RoundingMode.HALF_UP);
            if (initialValue.add(command.seed()).compareTo(required) < 0) {
                throw new IllegalArgumentException("인출식 VR 백테스트의 초기 자산은 " + required + " 이상이어야 합니다");
            }
        }
        // V=0인 채로 보유수량이 생기면 upperBand=0 → 매도 사다리가 $0.00 지정가로 나와 보유분이 증발한다
        // (운영에선 증권사가 $0 주문을 거부해 드러나지 않는 VrStrategy 사전조건) — API 경계에서 차단
        if (initialValue.signum() <= 0) {
            throw new IllegalArgumentException("VR 백테스트의 초기 V값(vrInitialValue)은 0보다 커야 합니다");
        }
    }

    // --- 캔들 조달 ---

    private List<DailyCandle> fetchCandles(BacktestCommand command) {
        // INFINITE만 전일종가 확보용 워밍업 프리픽스를 덧붙인다(엔진이 from 이전 캔들은 시뮬레이션하지 않고 종가만 이월)
        LocalDate fetchFrom = command.type() == Strategy.Type.INFINITE
                ? command.from().minusDays(INFINITE_WARMUP_DAYS)
                : command.from();
        return candlePort.fetchDailyCandles(command.ticker().name(), fetchFrom, command.to());
    }

    // --- PRIVACY 기준 매매표 조달 ---

    private Map<LocalDate, PrivacyTradeBase> loadPrivacyBases(List<DailyCandle> candles) {
        Map<LocalDate, PrivacyTradeBase> bases = new HashMap<>();
        for (DailyCandle candle : candles) {
            privacyTradePort.findTodayTrade(candle.date())
                    .filter(base -> appliesTo(base, candle.date()))
                    .ifPresent(base -> bases.put(candle.date(), base));
        }
        return bases;
    }

    // findTodayTrade는 "release_date >= 조회일" 중 가장 이른 1건을 준다 — 데이터가 없는 날엔 미래 기준표가 딸려와
    // 백테스트에선 look-ahead가 된다. 적용 거래일이 그날과 정확히 일치하는 기준표만 남긴다(주문 없는 표는 어차피 무의미)
    private static boolean appliesTo(PrivacyTradeBase base, LocalDate date) {
        return !base.trades().isEmpty() && date.equals(base.trades().getFirst().tradeDate());
    }

    // 요청 구간이 실제 기준표 데이터보다 이르면 1건만 요약 경고 — 시작일은 조회 결과에서 계산(상수 하드코딩 금지)
    private void addRangeClampWarning(List<DailyCandle> candles, Map<LocalDate, PrivacyTradeBase> bases,
                                      List<String> warnings) {
        if (bases.isEmpty()) return; // 구간 전체 결측은 엔진이 "기준 매매표 없음" 경고로 이미 요약한다
        LocalDate dataStart = Collections.min(bases.keySet());
        LocalDate simulationStart = candles.getFirst().date(); // 요청 from이 휴장일이면 첫 캔들이 실제 시작일
        if (dataStart.isAfter(simulationStart)) {
            warnings.add("기준 매매표 데이터가 " + dataStart + "부터 존재 — 그 이전 구간은 매매 없음");
        }
    }

    // --- 성과 요약 ---

    // 수익률 지표는 시작·끝 두 지점만으로 계산한다 — 연속 포인트 델타엔 VR 적립/인출 현금흐름이 섞여 성과로 오인된다
    private BacktestSummary summarize(BacktestEngine.Output output) {
        List<BacktestPoint> points = output.points();
        if (points.isEmpty()) throw new IllegalStateException("백테스트 구간에 시뮬레이션 가능한 거래일이 없습니다");

        BigDecimal firstAsset = points.getFirst().totalAsset();
        BigDecimal lastAsset = points.getLast().totalAsset();
        BigDecimal lastIndex = ReturnMetrics.normalize(lastAsset, firstAsset); // 100 기준 지수
        BigDecimal mdd = ReturnMetrics.maxDrawdown(points.stream().map(BacktestPoint::totalAsset).toList()); // scale-invariant

        long days = ChronoUnit.DAYS.between(points.getFirst().date(), points.getLast().date());
        BigDecimal cagr = days == 0 ? null : ReturnMetrics.annualizedReturn(lastIndex, 365.0 / days);

        return new BacktestSummary(lastAsset, points.getLast().principal(),
                ReturnMetrics.cumulativeReturn(lastIndex), cagr, mdd,
                output.tradeCount(), output.cycleCount());
    }
}
