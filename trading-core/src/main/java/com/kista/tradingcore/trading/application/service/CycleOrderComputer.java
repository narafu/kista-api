package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.matching.domain.model.*;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionInfiniteDetailPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCycleVrPort;
import com.kista.tradingcore.trading.application.port.output.StrategyInfiniteDetailPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVrDetailPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.broker.adapter.out.marketdata.AlpacaMarketDataAdapter;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery;
import com.kista.tradingcore.matching.domain.strategy.ReverseInfiniteStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// TradingService/TradingPreviewService가 공유하던 주문 계획 공통부 추출
// '전략 라우팅 → plan(PlanContext) → 잔고 유효성 검증'까지만 수행 — 저장·알림·반환 변환은 호출측 책임
@Slf4j
@Component
@RequiredArgsConstructor
class CycleOrderComputer {

    // 리버스모드 별지점 계산에 사용할 최근 종가 개수
    private static final int STAR_POINT_WINDOW = 5;

    private final CycleOrderStrategies cycleStrategies;   // 전략 타입별 주문 전략 라우터
    private final CyclePositionPort cyclePositionPort;    // 리버스모드 별지점 계산용
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;  // VR 사이클 상세 (value·poolLimitRate)
    private final StrategyVrDetailPort strategyVrDetailPort; // VR 전략 버전 상세 (bandWidth)
    private final OrderPort orderPort;                       // VR poolUsed 조회용
    private final AlpacaMarketDataAdapter alpaca;            // 리버스모드 별지점용 직전 5거래일 종가(공용 시세 일봉)

    // 전략 계산 + 주문 유효성 검증을 묶어 계산만 수행 (부수효과 없음)
    // currentCycle: INFINITE은 리버스모드 판단에 사용
    // currentPrice: VR 실주문 기준가격(PRIVACY는 소비하지 않음) — preview 시 null
    // Optional.empty() = 전략 차원 skip (예: PRIVACY 기준매매표 미수신)
    Optional<CycleOrderStrategy.OrderPlan> compute(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                                                   LocalDate tradeDate, StrategyCycle currentCycle,
                                                   PrivacyTradeBase privacyBase, UUID accountId, BigDecimal currentPrice) {
        return compute(balance, strategy, prevClosePrice, tradeDate, currentCycle, privacyBase, accountId, currentPrice, null);
    }

    // dcaInputs: 회차 판정 전략(recordsRounds)의 수집 입력(DcaPlanInputCollector) — 호출부가 수집해 복구 대상을 직접 다룬다, 그 외 null
    Optional<CycleOrderStrategy.OrderPlan> compute(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                                                   LocalDate tradeDate, StrategyCycle currentCycle,
                                                   PrivacyTradeBase privacyBase, UUID accountId, BigDecimal currentPrice,
                                                   CycleOrderStrategy.PlanContext.DcaPlanInputs dcaInputs) {
        Integer divisionCount = resolveDivisionCount(strategy, currentCycle);

        // 오늘의 리버스모드 여부와 첫날 여부를 cycle_position 최근 2건으로 판단
        boolean isReverseMode = false;
        boolean isFirstReverseDay = false;
        BigDecimal starPointPrice = null;
        if (strategy.isInfinite() && currentCycle != null) {
            List<CyclePositionInfiniteDetail> recent = cyclePositionInfiniteDetailPort.findLatestByCycleId(currentCycle.id(), 2);
            isReverseMode = !recent.isEmpty() && recent.getFirst().isReverseMode();
            isFirstReverseDay = isReverseMode && (recent.size() < 2 || !recent.get(1).isReverseMode());
            // 별지점: 리버스모드 2일차+에서만 계산 (첫날은 MOC 즉시 청산이라 별지점 불필요)
            if (isReverseMode && !isFirstReverseDay) {
                starPointPrice = computeStarPointPrice(strategy, currentCycle.id(), tradeDate);
            }
        }

        // 전략 전용 입력을 각 묶음으로 조립 — 타입 무관하게 채울 수 있는 값은 채움 (구현체가 자기 묶음만 소비)
        CycleOrderStrategy.PlanContext.InfiniteInputs infiniteInputs =
                new CycleOrderStrategy.PlanContext.InfiniteInputs(
                        divisionCount, prevClosePrice, starPointPrice, isReverseMode, isFirstReverseDay);
        // PRIVACY 평가가: 최신 cycle_position 종가(추가 투입 반영 행), 없으면 전일종가
        BigDecimal valuationPrice = prevClosePrice;
        if (strategy.isPrivacy() && currentCycle != null) {
            valuationPrice = cyclePositionPort.findLatestOne(currentCycle.id())
                    .map(CyclePosition::closingPrice).orElse(null);
            if (valuationPrice == null) valuationPrice = prevClosePrice;
        }
        CycleOrderStrategy.PlanContext.PrivacyInputs privacyInputs =
                new CycleOrderStrategy.PlanContext.PrivacyInputs(
                        valuationPrice, privacyBase == null ? null : privacyBase.toPlan(), currentPrice);

        // VR 전략 전용 입력 조립 — 사이클·버전 상세를 DB에서 조회, 미존재 시 데이터 무결성 오류로 fail-fast
        CycleOrderStrategy.PlanContext.VrInputs vrInputs = null;
        if (strategy.isVr()) {
            StrategyCycleVrDetail cycleVr = strategyCycleVrPort.findByCycleId(currentCycle.id())
                    .orElseThrow(() -> new IllegalStateException("VR 사이클 상세가 없습니다."));
            StrategyVrDetail vrDetail = strategyVrDetailPort.findByStrategyVersionId(currentCycle.strategyVersionId())
                    .orElseThrow(() -> new IllegalStateException("VR 전략 버전 상세가 없습니다."));
            BigDecimal poolUsed = orderPort.sumFilledBuyAmountByCycleId(currentCycle.id());
            // poolLimit(달러)은 개장 포지션의 예수금 × 고정 poolLimitRate 스냅샷
            CyclePosition openingPosition = cyclePositionPort.findFirstOne(currentCycle.id())
                    .orElseThrow(() -> new IllegalStateException("VR 시작 포지션이 없습니다."));
            BigDecimal poolLimit = StrategyVrDetail.poolLimitOf(openingPosition.deposit(), cycleVr.poolLimitRate());
            // referencePrice: bootstrap·일반 사다리 캡 판정 공용 기준가 — currentPrice 없으면(preview) 전일종가로 대체
            BigDecimal vrReferencePrice = currentPrice != null ? currentPrice : prevClosePrice;
            vrInputs = new CycleOrderStrategy.PlanContext.VrInputs(
                    cycleVr.value(), vrDetail.bandWidth(), poolLimit, poolUsed, vrReferencePrice, currentPrice,
                    vrDetail.recurringAmount());
        }

        CycleOrderStrategy orderStrategy = cycleStrategies.of(strategy.type());
        return orderStrategy.plan(new CycleOrderStrategy.PlanContext(
                balance, strategy.ticker(), tradeDate, accountId, infiniteInputs, privacyInputs, vrInputs, dcaInputs));
    }

    // 별지점 계산 — 직전 5거래일 종가 평균(공용 시세 일봉 RAW). 조회 실패·5개 미만이면 원장 행 평균으로 대체하고 warn
    // 미리보기 경로는 readOnly 트랜잭션 안에서 부른다 — 전일종가 조회와 같은 기존 구조, 어댑터 캐시(CANDLES_TTL)로 반복 호출을 줄인다
    private BigDecimal computeStarPointPrice(Strategy strategy, UUID cycleId, LocalDate tradeDate) {
        // 같은 거래일에 이미 저장된 리버스 LOC 매도가 있으면 그 가격이 그날의 별지점이다 — 누락된 매수 leg만 뒤에 계산될 때 출처(일봉/원장 대체)가 달라 매수가가 매도가 위로 가는 것을 막는다
        // 매수 leg는 가격 캡으로 낮아졌을 수 있어 쓰지 않는다
        Optional<BigDecimal> storedSellPrice = orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, tradeDate).stream()
                .filter(o -> ReverseInfiniteStrategy.LOC_SELL_LEG.equals(o.orderLeg()) && o.price() != null)
                .map(Order::price).findFirst();
        if (storedSellPrice.isPresent()) return storedSellPrice.get();
        LocalDate usSessionDate = tradeDate.minusDays(1); // TradingService.usSessionDate와 같은 규칙(KST 거래일 → 미국 세션일)
        Optional<List<BrokerCandle>> fetched = BrokerCallGuard.tryGet("strategyId=" + strategy.id(), () -> alpaca.dailyCandles(
                strategy.ticker().name(), DailyCandleQuery.lastBefore(usSessionDate, STAR_POINT_WINDOW, DailyCandleQuery.Adjustment.RAW)));
        List<BrokerCandle> candles = fetched.orElse(List.of());
        if (candles.size() >= STAR_POINT_WINDOW) {
            return average(candles.stream().map(BrokerCandle::close).toList());
        }
        // 조회 실패는 tryGet이 이미 warn — 성공했는데 모자랄 때만 따로 남긴다
        if (fetched.isPresent()) {
            log.warn("[strategyId={}] 별지점 일봉 {}개(필요 {}개) — 원장 행 평균으로 대체합니다", strategy.id(), candles.size(), STAR_POINT_WINDOW);
        }
        return starPointFromLedger(cycleId);
    }

    // 대체 경로: INFINITE 상세가 있는 최신 STAR_POINT_WINDOW(5)건(리포트·개장 행)의 종가 평균
    // 수동 체결 보정 행은 체결가가 closingPrice에 들어가므로 상세 유무로 제외한다
    // 포지션이 없으면(첫날) null 반환 — 호출측에서 첫날 분기 처리
    private BigDecimal starPointFromLedger(UUID cycleId) {
        List<CyclePosition> recentPositions = cyclePositionPort.findLatestWithInfiniteDetailByCycleId(cycleId, STAR_POINT_WINDOW);
        List<BigDecimal> closingPrices = recentPositions.stream()
                .map(CyclePosition::closingPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .toList();
        return closingPrices.isEmpty() ? null : average(closingPrices);
    }

    private static BigDecimal average(List<BigDecimal> prices) {
        BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(prices.size()), Currency.USD.amountScale(), RoundingMode.HALF_UP);
    }

    private Integer resolveDivisionCount(Strategy strategy, StrategyCycle currentCycle) {
        if (!strategy.isInfinite()) {
            return null;
        }
        if (currentCycle != null && currentCycle.strategyVersionId() != null) {
            return strategyInfiniteDetailPort.findByStrategyVersionId(currentCycle.strategyVersionId())
                    .map(StrategyInfiniteDetail::divisionCount)
                    .orElse(StrategyDefaults.DEFAULT_DIVISION_COUNT);
        }
        return strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                .map(StrategyInfiniteDetail::divisionCount)
                .orElse(StrategyDefaults.DEFAULT_DIVISION_COUNT);
    }
}
