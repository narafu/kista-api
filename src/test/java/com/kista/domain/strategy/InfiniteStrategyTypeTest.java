package com.kista.domain.strategy;

import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;
import static com.kista.domain.model.order.Order.OrderType.LOC;
import static com.kista.domain.model.order.Order.OrderType.MOC;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InfiniteStrategy 매매 변수 계산 검증")
class InfiniteStrategyTypeTest {

    private final InfiniteStrategy strategy = new InfiniteStrategy();
    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);

    // ── buildOrders() 시나리오 ──────────────────────────────────────────────

    @Test
    @DisplayName("buildOrders 전반 Q>0: LOC매수①② + LOC매도 + 지정가매도 = 4건")
    void buildOrders_frontHalf_withQuantity() {
        // A=20, Q=10, D=1000 → B=1200, K=60, T≈3.33, S>0 (전반)
        // G=20×(1+S)≈22.67, P=24.00
        // BUY①: floor(60/2/20)=1, BUY②: floor(60/2/22.67)=1
        // LOC SELL: 10/4=2, LIMIT SELL: 10-2=8
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"),
                new BigDecimal("1000"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("21"));

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(4);
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY && o.price().compareTo(position.averagePrice()) == 0);
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == BUY && o.price().compareTo(position.referencePrice()) == 0);
        assertThat(orders.get(2)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.quantity() == 2);
        assertThat(orders.get(3)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.quantity() == 8 && o.price().compareTo(position.targetPrice()) == 0);
        assertThat(orders).allMatch(o -> o.ticker() == Ticker.SOXL && o.tradeDate().equals(TODAY));
    }

    @Test
    @DisplayName("buildOrders 0회차: 최근 종가를 평단가 대용으로 사용 — LOC매수①(종가) + LOC매수②(종가 기반 기준가) = 2건, SELL 없음")
    void buildOrders_zeroRound_usesPrevClose() {
        // 0회차: averagePrice=prevClose=22 (현재가 20과 무관), Q=0, D=1000 → B=1000, K=50
        // G=22×1.20=26.40
        // BUY①: floor(50/2/22)=1(비용=22), BUY②: floor((50-22)/26.40)=floor(1.06)=1
        // SELL: Q/4=0 → 없음
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("22"));

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(2);
        // 평단가 대용 = 최근 종가(22), 현재가(20)가 아님
        assertThat(position.averagePrice()).isEqualByComparingTo("22");
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.price().compareTo(position.averagePrice()) == 0); // 종가 기준
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == BUY
                && o.price().compareTo(position.referencePrice()) == 0); // 종가 기반 referencePrice
    }

    @Test
    @DisplayName("buildOrders 후반 K>D: MOC 매도 1건만")
    void buildOrders_backHalf_insufficientDeposit() {
        // A=5, Q=200, D=50 → B=1050, K=52.50, T≈19.05 (후반), K(52.50)>D(50)
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"),
                new BigDecimal("50"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("4.8"));

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst()).matches(o -> o.orderType() == MOC && o.direction() == SELL && o.quantity() == 50); // 200/4
    }

    @Test
    @DisplayName("buildOrders 후반 K<=D: LOC매수 + LOC매도 + 지정가매도 = 3건")
    void buildOrders_backHalf_sufficientDeposit() {
        // A=5, Q=200, D=100 → B=1100, K=55, T≈18.18 (후반), K(55)<=D(100)
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"),
                new BigDecimal("100"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("4.9"));

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(3);
        assertThat(orders.getFirst()).matches(o -> o.orderType() == LOC && o.direction() == BUY);
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.quantity() == 50); // 200/4
        assertThat(orders.get(2)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.quantity() == 150); // 200-50
    }

    @Test
    @DisplayName("buildOrders TQQQ: ticker가 TQQQ로 설정됨")
    void buildOrders_tqqq_tickerSet() {
        // 0회차: A=prevClose=10 (현재가 9와 무관)
        // B=2000, K=100, G=10×1.15=11.50
        // BUY①: floor(100/2/10)=5, BUY②: floor(100/2/11.50)=4 → 주문 있음
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("2000"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.TQQQ, new BigDecimal("10"));

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).isNotEmpty();
        assertThat(orders).allMatch(o -> o.ticker() == Ticker.TQQQ);
    }
}
