package com.kista.trading.domain.strategy;

import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.ReverseModePosition;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReverseInfiniteStrategy 주문 leg 검증")
class ReverseInfiniteStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);
    private final ReverseInfiniteStrategy strategy = new ReverseInfiniteStrategy();

    @Test
    @DisplayName("리버스 첫날은 MOC SELL leg 한 건을 생성한다")
    void buildFirstDayOrders_assignsMocSellLeg() {
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), true);

        List<Order> orders = strategy.buildFirstDayOrders(position, TODAY);

        assertThat(orders).extracting(Order::orderLeg)
                .containsExactly("REVERSE_INFINITE_MOC_SELL");
    }

    @Test
    @DisplayName("리버스 일반일은 LOC SELL과 LOC BUY leg를 순서대로 생성한다")
    void buildOrders_assignsLocSellAndBuyLegs() {
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).extracting(Order::orderLeg)
                .containsExactly("REVERSE_INFINITE_LOC_SELL", "REVERSE_INFINITE_LOC_BUY");
    }

    @Test
    @DisplayName("쿼터매수 예산 소진 시 동일 수량 MOC SELL leg 한 건만 생성한다")
    void buildOrders_quotaBuyExhausted_producesMocSellOnly() {
        // usdDeposit=3.00 → 쿼터매수 예산 소진, holdings=100·divisionCount=20 → calcLocSellQuantity=10
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(1);
        Order order = orders.get(0);
        assertThat(order.orderLeg()).isEqualTo("REVERSE_INFINITE_QUOTA_MOC_SELL");
        assertThat(order.orderType()).isEqualTo(Order.OrderType.MOC);
        assertThat(order.direction()).isEqualTo(Order.OrderDirection.SELL);
        assertThat(order.quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("쿼터매수 예산 소진이지만 매도 수량도 0이면 빈 리스트를 반환한다")
    void buildOrders_quotaBuyExhausted_emptyWhenSellQuantityZero() {
        // holdings=5·divisionCount=20 → calcLocSellQuantity=0
        ReverseModePosition position = new ReverseModePosition(
                5, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).isEmpty();
    }
}
