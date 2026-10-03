package com.kista.tradingstats.application.service;

import com.kista.tradingstats.domain.backtest.BacktestEngine;
import com.kista.tradingstats.domain.model.backtest.BacktestCommand;
import com.kista.tradingstats.domain.model.backtest.BacktestPoint;
import com.kista.tradingstats.domain.model.backtest.BacktestResult;
import com.kista.tradingstats.domain.model.backtest.BacktestSummary;
import com.kista.tradingstats.domain.model.DailyCandle;
import com.kista.privacy.domain.model.PrivacyDates;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.sharedkernel.ReturnMetrics;
import com.kista.matching.domain.model.BootstrapPosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.trading.domain.strategy.VrRampValidator;
import com.kista.trading.domain.strategy.StrategyCreationResolver;
import com.kista.sharedkernel.RecurringMode;
import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.tradingstats.application.usecase.BacktestUseCase;
import com.kista.tradingstats.application.port.output.HistoricalCandlePort;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kista.sharedkernel.StrategyType;

@Service
@RequiredArgsConstructor
class BacktestService implements BacktestUseCase {

    private static final String FILL_MODEL_WARNING =
            "체결은 일봉 시가·고가·저가·종가로 판정합니다. 지정가 주문은 장중 가격이 지정가에 닿으면 전량 체결된 것으로 보고"
                    + "(시가가 더 유리하게 열리면 시가로 체결), LOC·MOC 주문은 종가로 체결합니다. "
                    + "부분 체결과 호가 대기열은 반영하지 않아 실제보다 낙관적일 수 있습니다.";
    private static final String ORDER_TIMING_WARNING =
            "일봉 단위 시뮬레이션이라 장 시작/장 마감 접수 시점 구분은 반영되지 않습니다.";
    private static final String COST_WARNING =
            "수수료·세금·환전 비용과 배당금은 반영하지 않아 실제 수익보다 높거나 낮게 보일 수 있습니다.";
    private static final int MIN_CAGR_DAYS = 365; // 이보다 짧은 구간은 연환산하면 과대·과소 표시되므로 CAGR을 내지 않는다
    private static final String VR_CASH_FLOW_WARNING =
            "적립식/인출식 설정 시 누적 수익률·CAGR·MDD가 외부 입출금을 반영하지 않아 "
                    + "실제보다 낙관적이거나 비관적으로 보일 수 있습니다.";

    private final HistoricalCandlePort candlePort;         // 과거 일봉 조달 (Alpaca)
    private final PrivacyTradePort privacyTradePort;       // PRIVACY 기준 매매표 조회
    private final CycleOrderStrategies cycleOrderStrategies; // 전략 capability 라우터 (엔진에 그대로 위임)
    private final StrategyCreationPolicyPort strategyCreationPolicyPort; // VR bandWidth/intervalWeeks 허용값 — 운영 등록과 동일 정책

    @Override
    public BacktestResult run(BacktestCommand command) {
        validate(command);

        List<DailyCandle> candles = fetchCandles(command); // 비어 있으면 어댑터가 이미 IllegalArgumentException
        // VR 인출식 최소자산은 시작 시점 시장가가 필요해 캔들 조달 뒤에 검증한다
        if (command.type() == StrategyType.VR) validateVrWithdrawal(command, candles.getFirst().close());
        List<String> warnings = new ArrayList<>();

        // PRIVACY만 날짜별 기준 매매표를 미리 조달한다 — 도메인 엔진은 DB I/O를 할 수 없다
        Map<LocalDate, PrivacyTradeBase> privacyBases = Map.of();
        if (command.type() == StrategyType.PRIVACY) {
            privacyBases = loadPrivacyBases(candles);
            addRangeClampWarning(candles, privacyBases, warnings);
        }

        BacktestEngine engine = new BacktestEngine(cycleOrderStrategies); // 무상태 순수 클래스 — Spring 빈 아님
        BacktestEngine.Output output = command.type() == StrategyType.PRIVACY
                ? engine.run(candles, command, privacyBases)
                : engine.run(candles, command);

        warnings.addAll(0, output.warnings()); // 엔진 경고를 앞, 항상 붙는 안내를 뒤로
        warnings.add(FILL_MODEL_WARNING);
        warnings.add(ORDER_TIMING_WARNING);
        warnings.add(COST_WARNING);
        if (command.type() == StrategyType.VR && command.vrRecurringAmount() != 0) warnings.add(VR_CASH_FLOW_WARNING);

        return new BacktestResult(output.points(), summarize(output), List.copyOf(warnings));
    }

    // --- 검증 (전부 IllegalArgumentException → GlobalExceptionHandler 400) ---

    private void validate(BacktestCommand command) {
        if (!command.type().availableTickers().contains(command.ticker())) {
            throw new IllegalArgumentException(
                    command.type() + " 전략이 지원하지 않는 종목입니다: " + command.ticker());
        }
        if (command.seed() != null && command.seed().signum() < 0) {
            throw new IllegalArgumentException("시드(seed)는 0 이상이어야 합니다");
        }
        int initialHoldings = BootstrapPosition.validate(command.initialHoldings(), command.initialAvgPrice());
        boolean hasSeed = command.seed() != null && command.seed().signum() > 0;
        if (!hasSeed && initialHoldings <= 0) {
            throw new IllegalArgumentException("시드(seed) 또는 기존 보유(initialHoldings·initialAvgPrice) 중 하나는 있어야 합니다");
        }
        if (command.from().isAfter(command.to())) {
            throw new IllegalArgumentException("시작일(from)이 종료일(to)보다 늦을 수 없습니다");
        }
        if (command.type() == StrategyType.INFINITE && command.divisionCount() != null) {
            List<Integer> allowed = cycleOrderStrategies.of(StrategyType.INFINITE).availableDivisionCounts();
            if (!allowed.contains(command.divisionCount())) {
                throw new IllegalArgumentException("지원하지 않는 분할 수(divisionCount)입니다: " + command.divisionCount()
                        + ", 허용값=" + allowed);
            }
        }
        if (command.type() == StrategyType.VR) validateVr(command);
    }

    // 운영 StrategyCreationService.validateVrCommand()와 동일 규칙 — 허용값·램프 (인출식 최소자산은 validateVrWithdrawal)
    private void validateVr(BacktestCommand command) {
        if (command.vrBandWidth() == null || command.vrBandWidth().signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 밴드 폭(vrBandWidth)은 0보다 커야 합니다");
        }
        if (command.vrIntervalWeeks() == null || command.vrIntervalWeeks() <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(vrIntervalWeeks)는 1 이상이어야 합니다");
        }
        if (command.vrInitialValue() != null && command.vrInitialValue().signum() < 0) {
            throw new IllegalArgumentException("VR 백테스트의 초기 V값(vrInitialValue)은 0 이상이어야 합니다");
        }
        validateVrAllowedValues(command);
        command.vrRamp().validate(command.vrIntervalWeeks(), command.vrBandWidth());
    }

    // 인출식 최소자산·거치식 게이트 — 운영과 동일하게 gate는 V값(직접 입력 우선) 기준, 필요자산 비교는 시장가 평가금 기준
    private void validateVrWithdrawal(BacktestCommand command, BigDecimal day0Close) {
        BigDecimal seed = command.seedOrZero();
        BigDecimal stockValue = BacktestEngine.initialStockValue(command, day0Close);
        BigDecimal vrValue = BacktestEngine.initialVrValue(command, day0Close);
        VrRampValidator.validateWithdrawalSufficiency(command.vrRecurringAmount(), command.vrIntervalWeeks(),
                vrValue.add(seed), stockValue.add(seed));
    }

    // bandWidth/intervalWeeks/입출금 방향이 런타임 생성 정책 허용값 안인지 — enabled 여부는 백테스트에선 보지 않는다
    // StrategyFieldSettings.resolve()는 영어 메시지라 쓰지 않고 같은 비교(BigDecimal은 compareTo)를 한국어 문구로 직접 수행한다
    private void validateVrAllowedValues(BacktestCommand command) {
        StrategyCreationSettings settings = strategyCreationPolicyPort.find(StrategyType.VR)
                .orElseThrow(() -> new IllegalArgumentException("VR 전략 생성 정책이 없어 백테스트를 실행할 수 없습니다."));
        List<BigDecimal> bandWidths = settings.bandWidth().allowedValues();
        if (bandWidths.stream().noneMatch(v -> v.compareTo(command.vrBandWidth()) == 0)) {
            throw new IllegalArgumentException("지원하지 않는 밴드 폭(vrBandWidth)입니다: " + command.vrBandWidth()
                    + ", 허용값=" + bandWidths);
        }
        // 적립/거치/인출 방향도 운영 VrCreationResolver와 같은 정책 — 고정 정책이면 allowedValues=[HOLD]라 같은 비교로 막힌다
        RecurringMode mode = StrategyCreationResolver.recurringModeOf(command.vrRecurringAmount());
        if (!settings.recurringMode().allowedValues().contains(mode)) {
            throw new IllegalArgumentException("지원하지 않는 입출금 방식(vrRecurringAmount)입니다: " + mode
                    + ", 허용값=" + settings.recurringMode().allowedValues());
        }
        List<Integer> intervals = settings.intervalWeeks().allowedValues();
        if (!intervals.contains(command.vrIntervalWeeks())) {
            throw new IllegalArgumentException("지원하지 않는 리밸런싱 주기(vrIntervalWeeks)입니다: " + command.vrIntervalWeeks()
                    + ", 허용값=" + intervals);
        }
    }

    // --- 캔들 조달 ---

    private List<DailyCandle> fetchCandles(BacktestCommand command) {
        return candlePort.fetchDailyCandles(command.ticker().name(), command.from(), command.to());
    }

    // --- PRIVACY 기준 매매표 조달 ---

    private Map<LocalDate, PrivacyTradeBase> loadPrivacyBases(List<DailyCandle> candles) {
        Map<LocalDate, PrivacyTradeBase> bases = new HashMap<>();
        // 첫 캔들 세션은 엔진이 계획할 전날이 없어 쓰이지 않는다 — 두 번째 캔들부터 조회
        for (DailyCandle candle : candles.subList(Math.min(1, candles.size()), candles.size())) {
            // 캔들 날짜는 US 세션일이고 findTodayTrade의 파라미터는 KST 거래일이다 — 세션 D에 적용되는 기준표는
            // 발행일이 D인 표(= KST 거래일 D+1)이므로 발행일→거래일 헬퍼로 기준을 맞춰 조회·판별한다
            LocalDate applied = PrivacyDates.tradeDateOf(candle.date());
            privacyTradePort.findTodayTrade(applied)
                    .filter(base -> appliesTo(base, applied))
                    .ifPresent(base -> bases.put(candle.date(), base)); // 맵 키는 엔진이 읽는 캔들 날짜 그대로
        }
        return bases;
    }

    // findTodayTrade는 "release_date >= 조회일" 중 가장 이른 1건을 준다 — 데이터가 없는 날엔 미래 기준표가 딸려와
    // 백테스트에선 look-ahead가 된다. 적용 거래일(appliedTradeDate, KST)이 정확히 일치하는 기준표만 남긴다(주문 없는 표는 어차피 무의미)
    private static boolean appliesTo(PrivacyTradeBase base, LocalDate appliedTradeDate) {
        return !base.trades().isEmpty() && appliedTradeDate.equals(base.trades().getFirst().tradeDate());
    }

    // 요청 구간이 실제 기준표 데이터보다 이르면 1건만 요약 경고 — 시작일은 조회 결과에서 계산(상수 하드코딩 금지)
    private void addRangeClampWarning(List<DailyCandle> candles, Map<LocalDate, PrivacyTradeBase> bases,
                                      List<String> warnings) {
        if (bases.isEmpty()) return; // 구간 전체 결측은 엔진이 기준 매매표 결측 구간 경고로 이미 요약한다
        LocalDate dataStart = Collections.min(bases.keySet());
        // 매매 가능한 첫 세션 = 두 번째 캔들(첫 캔들 처리 끝에 계획한 주문이 처음 체결되는 세션)
        if (candles.size() < 2) return;
        LocalDate simulationStart = candles.get(1).date();
        if (dataStart.isAfter(simulationStart)) {
            warnings.add("기준 매매표 데이터가 " + dataStart + "부터 존재해 그 이전 구간은 매매하지 않았습니다.");
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
        BigDecimal cagr = days < MIN_CAGR_DAYS ? null : ReturnMetrics.annualizedReturn(lastIndex, 365.0 / days);

        return new BacktestSummary(lastAsset, points.getLast().principal(),
                ReturnMetrics.cumulativeReturn(lastIndex), cagr, mdd,
                output.tradeCount(), output.cycleCount());
    }
}
