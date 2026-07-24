package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.SellSufficiencyPreview;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

// 바로주문 미리보기에서 대상 전략의 SELL이 판매가능수량 부족으로 거절될지 근사 판정한다
// BUY 예산 경쟁(TradingBuyCompetitionSimulator)과 달리 계좌당 종목 유일성 제약상 동일 계좌 내
// 타 전략과 경쟁이 발생하지 않으므로 우선순위 정렬 없이 단일 종목 기준으로만 판정한다
// package-private — application/service/trading 패키지 전용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingSellSufficiencySimulator {

    private final BrokerAdapterRegistry registry; // live 판매가능수량 조회
    private final OrderPort orderPort;             // 동일 계좌·종목·거래일 기존 예약 SELL 수량 조회

    SellSufficiencyPreview simulate(Strategy strategy, Account account, List<Order> sellOrders, LocalDate tradeDate) {
        int requiredQuantity = sellOrders.stream().mapToInt(Order::quantity).sum();

        int sellableQuantity;
        try {
            sellableQuantity = registry.require(account, SellableQuantityPort.class)
                    .getSellableQuantity(strategy.ticker(), account)
                    .quantity();
        } catch (KisApiException | TossApiException e) {
            log.warn("대상 전략 판매가능수량 조회 실패, 충족 판정 생략: strategyId={}, error={}", strategy.id(), e.getMessage());
            return SellSufficiencyPreview.unavailable(requiredQuantity);
        }

        int reservedQuantity = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, strategy.ticker());
        boolean sufficient = reservedQuantity + requiredQuantity <= sellableQuantity;
        return new SellSufficiencyPreview(sufficient, sellableQuantity, reservedQuantity, requiredQuantity, false);
    }
}
