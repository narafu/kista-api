package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CandlePort;
import com.kista.tradingcore.broker.application.port.output.SellableQuantityPort;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy.OrderPlan;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// DCA 배치 회차 계획 — 기존 주문 복구·timing·실행일·이력 판정 후 데이터 조회 → DcaCycleOrderStrategy.decide → skip이면 이력·알림 기록
@Slf4j
@Component
@RequiredArgsConstructor
class DcaRoundPlanner {

    private static final int PREV_CLOSE_CANDLES = 5; // 전일 종가 확보용 봉 수 (휴장 여유분)

    private final MarketCalendarPort marketCalendarPort;
    private final CandlePort candlePort;
    private final SellableQuantityPort sellableQuantityPort;
    private final OrderPort orderPort;
    private final StrategyDcaDetailPort dcaDetailPort;
    private final DcaExecutionPort dcaExecutionPort;
    private final DcaExecutionRecorder recorder;
    private final DcaCycleOrderStrategy dcaStrategy;

    // empty = 이번 배치에서 DCA 신규 주문 없음 (비실행일·다른 timing·이미 판정·skip)
    Optional<OrderPlan> plan(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings, List<Order> existingOrders) {
        Strategy strategy = ctx.strategy();
        DcaConfig config = dcaDetailPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("DCA 설정이 없습니다: " + strategy.id()));

        if (dcaExecutionPort.find(strategy.id(), tradeDate).isPresent()) return Optional.empty(); // 이미 판정됨

        // 주문 저장 후 이력 기록 전 중단된 재개 — 기존 DCA 주문으로 ORDERED 복구 (다른 timing 배치에서도 복구되도록 timing 판정보다 먼저)
        Optional<Order> existing = existingOrders.stream().filter(o -> config.orderLeg().equals(o.orderLeg())).findFirst();
        if (existing.isPresent()) {
            recorder.recordOrdered(ctx, tradeDate, existing.get(), null);
            return Optional.empty();
        }

        if (!creatableTimings.contains(config.timing())) return Optional.empty();
        LocalDate usSession = marketCalendarPort.usSessionDateOf(tradeDate);
        if (!DcaSchedule.isExecutionDay(usSession, config, marketCalendarPort::isUsSessionOpen)) return Optional.empty();

        // PLANNED/PLACED 외 상태(FILLED 등)로 넘어간 당일 DCA 주문 — 이력만 누락된 경우 재주문하지 않고 복구
        Optional<Order> terminal = orderPort.findByStrategyId(strategy.id(), tradeDate, tradeDate).stream()
                .filter(o -> config.orderLeg().equals(o.orderLeg())).findFirst();
        if (terminal.isPresent()) {
            recorder.recordOrdered(ctx, tradeDate, terminal.get(), null);
            return Optional.empty();
        }

        BigDecimal prevClose = loadPrevClose(strategy.ticker(), usSession);
        DcaDecision decision = dcaStrategy.decide(new DcaCycleOrderStrategy.DcaInputs(config, strategy.ticker(), tradeDate,
                prevClose == null ? null : List.of(prevClose), loadSellable(ctx, config, tradeDate), null)); // 임시 shim — Task 4가 250봉·실잔고로 대체
        return switch (decision) {
            case DcaDecision.Planned p -> Optional.of(new OrderPlan(null, null, List.of(p.order())));
            case DcaDecision.Skipped s -> {
                recorder.recordSkipped(ctx, tradeDate, s.reason(), s.detail(), s.referencePrice());
                yield Optional.empty();
            }
        };
    }

    // 조회 US 세션 이전 마지막 확정 종가 — 실패·부족 시 null(DATA_UNAVAILABLE)
    private BigDecimal loadPrevClose(StrategyTicker ticker, LocalDate usSession) {
        try {
            List<BrokerCandle> candles = candlePort.getDailyCandlesBefore(ticker.name(), usSession, PREV_CLOSE_CANDLES);
            return candles.isEmpty() ? null : candles.getLast().close();
        } catch (RuntimeException e) {
            log.warn("[DCA] 일봉 조회 실패: ticker={}, usSession={}", ticker, usSession, e);
            return null;
        }
    }

    // SELL만 — 판매가능수량 − 같은 계좌·거래일·종목 기존 PLANNED/PLACED SELL 예약분, 실패 시 null
    private Integer loadSellable(BatchContext ctx, DcaConfig config, LocalDate tradeDate) {
        if (config.direction() != OrderDirection.SELL) return null;
        try {
            int sellable = sellableQuantityPort.getSellableQuantity(ctx.strategy().ticker(), ctx.account().brokerRef()).quantity();
            int reserved = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                    ctx.account().id(), tradeDate, ctx.strategy().ticker());
            return Math.max(0, sellable - reserved);
        } catch (RuntimeException e) {
            log.warn("[DCA] 판매가능수량 조회 실패: strategy={}", ctx.strategy().id(), e);
            return null;
        }
    }
}
