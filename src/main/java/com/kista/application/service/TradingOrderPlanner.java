package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyTradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// package-private — application/service 패키지 전용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingOrderPlanner {

    private final InfiniteTradingStrategy infiniteStrategy;
    private final PrivacyTradingStrategy privacyStrategy;
    private final OrderPort orderPort;

    // INFINITE 전략 계산 결과 묶음
    record InfiniteCalc(InfinitePosition position, List<Order> orders) {}

    // INFINITE 전략 계산 (execute/preview 공통) — holdings==0 && price==null 방어
    InfiniteCalc calcInfinite(AccountBalance balance, TradingCycle cycle,
                               BigDecimal price, LocalDate today, String label) {
        if (balance.holdings() == 0 && price == null) {
            throw new IllegalStateException("현재가 조회 실패: " + cycle.ticker().name());
        }
        InfinitePosition position = new InfinitePosition(balance, cycle.ticker(), price);
        List<Order> orders = infiniteStrategy.buildOrders(position, today);
        log.info("[{}] 전략 계산: priceOffsetRate={}, currentRound={}, unitAmount={}, orders={}",
                label, position.priceOffsetRate(), position.currentRound(),
                position.unitAmount(), orders.size());
        return new InfiniteCalc(position, orders);
    }

    // PRIVACY 전략 계산
    List<Order> calcPrivacy(AccountBalance balance, BigDecimal initialUsdDeposit, PrivacyTradeBase privacyTradeBase) {
        return privacyStrategy.buildOrders(balance, initialUsdDeposit, privacyTradeBase);
    }

    // 이미 계산된 templates를 orders에 PLANNED 상태로 저장
    void savePlannedOrders(List<Order> templates, Account account) {
        List<Order> planned = templates.stream()
                .map(o -> Order.plan(o, account.id()))
                .toList();
        orderPort.saveAll(planned);
        log.info("[{}] 계획 주문 {}건 저장 (PLANNED)", account.nickname(), planned.size());
    }
}
