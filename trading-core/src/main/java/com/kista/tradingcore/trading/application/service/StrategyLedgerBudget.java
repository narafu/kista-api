package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PriceCapPolicy;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 전략 자기 원장(cycle_position.usd_deposit) 기준 BUY 상한 — 계좌 단위 예산 배정(live 잔고 조회)을 대체하는 순수 계산
@Component
@RequiredArgsConstructor
class StrategyLedgerBudget {

    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final TradingBatchRunPort batchRunPort;
    private final CycleOrderStrategies cycleOrderStrategies;

    // 원장 적용 결과 — orders: SELL 그대로 + BUY(원본·축소안·제거), requiredBuyUsd: 원본 BUY 총액
    record LedgerFit(List<PlannedOrder> orders, BigDecimal requiredBuyUsd, boolean trimmed, boolean rejected) {}

    // 사이클의 신규 BUY 가용 한도 — 최신 원장(첫 거래일처럼 포지션이 없으면 시작 금액)에서 같은 날 저장된 BUY(PLANNED·SUBMITTING·PLACED)와 리포트 전 취소 BUY의 보류 체결분을 한 번만 뺀다
    BigDecimal remaining(StrategyCycle cycle, LocalDate tradeDate) {
        return remaining(cycle, cyclePositionPort.findLatestOne(cycle.id()),
                orderPort.findPlannedOrPlacedByCycleAndDate(cycle.id(), tradeDate),
                unreportedCancelledBuys(cycle, tradeDate));
    }

    // 이미 조회한 최신 포지션·같은 날 PLANNED/SUBMITTING/PLACED 주문·리포트 전 취소 BUY(unreportedCancelledBuys)로 계산하는 순수 버전 — 미리보기·인출이 미리 모은 목록을 넘긴다(미리보기의 취소 목록은 전략당 조회), 공식은 여기 한 곳
    BigDecimal remaining(StrategyCycle cycle, Optional<CyclePosition> latest, List<Order> sameDayOrders, List<Order> unreportedCancelled) {
        BigDecimal ledger = latest.map(CyclePosition::usdDeposit).orElse(cycle.startAmount());
        if (ledger == null) ledger = BigDecimal.ZERO;
        BigDecimal committed = sameDayOrders.stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .map(o -> o.price().multiply(o.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return ledger.subtract(committed).subtract(pendingCancelledFill(unreportedCancelled));
    }

    // 마감 리포트 전 거래일의 취소 BUY(증권사 접수분, 레그 확인된 것) — 리포트 후엔 체결분이 이미 장부에 반영돼 빈 목록
    List<Order> unreportedCancelledBuys(StrategyCycle cycle, LocalDate tradeDate) {
        if (batchRunPort.isReported(tradeDate, cycle.strategyId())) return List.of();
        return orderPort.findCancelledByCycleAndDate(cycle.id(), tradeDate).stream()
                .filter(o -> o.direction() == OrderDirection.BUY && o.externalOrderId() != null && !Order.UNKNOWN_LEG.equals(o.orderLeg()))
                .toList();
    }

    // 보류 체결분 — 체결량 기록이 있으면 체결 금액, 모름(null, 체결 조회 실패)이면 주문 금액 전체를 상한으로 본다
    BigDecimal pendingCancelledFill(List<Order> unreportedCancelled) {
        return unreportedCancelled.stream()
                .map(o -> o.filledQuantity() == null
                        ? o.price().multiply(o.quantity())
                        : o.filledPrice() == null ? BigDecimal.ZERO : o.filledQuantity().multiply(o.filledPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // BUY 총액이 budget을 넘으면 전략 축소안(1회)을 시도하고, 그래도 넘으면 BUY 전체를 제거(SELL 유지)한다
    LedgerFit fit(StrategyType type, List<PlannedOrder> orders, BigDecimal budget) {
        CycleOrderStrategy strategy = cycleOrderStrategies.of(type);
        BigDecimal required = AccountBalance.buyTotal(orders);
        if (required.compareTo(budget) <= 0) {
            return new LedgerFit(orders, required, false, false);
        }
        List<PlannedOrder> buys = orders.stream().filter(o -> o.direction() == OrderDirection.BUY).toList();
        List<PlannedOrder> fitted = strategy.fitBuysToBudget(buys, budget);
        if (!fitted.isEmpty() && AccountBalance.buyTotal(fitted).compareTo(budget) <= 0) {
            return new LedgerFit(PriceCapPolicy.replaceBuysPreservingOrder(orders, fitted), required, true, false);
        }
        return new LedgerFit(PriceCapPolicy.replaceBuysPreservingOrder(orders, List.of()), required, false, true);
    }
}
