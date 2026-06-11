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

    // ── buildCappedBuyOrders() 시나리오 ──────────────────────────────────────
    // holdings=0 → unitAmount(K) = usdDeposit ÷ 20

    private InfinitePosition positionWithUnitAmount(String usdDeposit) {
        return new InfinitePosition(
                new AccountBalance(0, null, new BigDecimal(usdDeposit)),
                Ticker.SOXL, new BigDecimal("10.00"));
    }

    private Order buy(String price, int quantity) {
        return Order.planned(TODAY, Ticker.SOXL, LOC, BUY, quantity, new BigDecimal(price));
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 2건, 캡 후 가격 상이: ①②+보정 3건 = 5건")
    void buildCappedBuyOrders_twoBuys_distinctCaps_recalculatesBoth() {
        // cap=55, K=1000, r=0.20 / buy①=60(>cap→55), buy②=52(≤cap)
        // qty1 = floor((1000/2) / 55) = 9 / remaining = (1000-55×9)×1.20 = 606 / qty2 = floor(606/52) = 11
        // 보정 3회: K/(20+1)=47.62, K/(21+1)=45.45, K/(22+1)=43.48 — 각 1주 LOC
        InfinitePosition position = positionWithUnitAmount("20000");
        List<Order> buyOrders = List.of(buy("60.00", 1), buy("52.00", 1));

        List<Order> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        assertThat(result).hasSize(5);
        assertThat(result.get(0).quantity()).isEqualTo(9);
        assertThat(result.get(0).price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).quantity()).isEqualTo(11);
        assertThat(result.get(1).price()).isEqualByComparingTo("52.00");
        assertThat(result.get(2).quantity()).isEqualTo(1);
        assertThat(result.get(2).price()).isEqualByComparingTo("47.62");
        assertThat(result.get(3).quantity()).isEqualTo(1);
        assertThat(result.get(3).price()).isEqualByComparingTo("45.45");
        assertThat(result.get(4).quantity()).isEqualTo(1);
        assertThat(result.get(4).price()).isEqualByComparingTo("43.48");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 전반 2건, 동일 캡으로 수렴: 병합 1건+보정 3건 = 4건")
    void buildCappedBuyOrders_twoBuys_convergeToSameCap_mergesIntoOne() {
        // cap=55, buy①=70·buy②=60 모두 55로 캡 → 동일 가격이면 병합
        // qty1 = floor(500/55) = 9 / remaining = 606 / qty2 = floor(606/55) = 11 / merged = 20
        InfinitePosition position = positionWithUnitAmount("20000");
        List<Order> buyOrders = List.of(buy("70.00", 1), buy("60.00", 1));

        List<Order> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        // 병합 1건 + 보정 3회: K/(20+1)=47.62, K/(21+1)=45.45, K/(22+1)=43.48 — 각 1주 LOC
        assertThat(result).hasSize(4);
        assertThat(result.getFirst().quantity()).isEqualTo(20);
        assertThat(result.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).price()).isEqualByComparingTo("47.62");
        assertThat(result.get(2).price()).isEqualByComparingTo("45.45");
        assertThat(result.get(3).price()).isEqualByComparingTo("43.48");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 후반 1건: 재산정 1건+보정 3건 = 4건")
    void buildCappedBuyOrders_singleBuy_capped_recalculatesQuantity() {
        // 후반 단일 LOC 매수 — cap=55, K=1000 / qty = floor(1000/55) = 18
        InfinitePosition position = positionWithUnitAmount("20000");
        List<Order> buyOrders = List.of(buy("70.00", 1));

        List<Order> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        // 재산정 1건 + 보정 3회: K/(18+1)=52.63, K/(19+1)=50.00, K/(20+1)=47.62 — 각 1주 LOC
        assertThat(result).hasSize(4);
        assertThat(result.getFirst().quantity()).isEqualTo(18);
        assertThat(result.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(result.get(1).price()).isEqualByComparingTo("52.63");
        assertThat(result.get(2).price()).isEqualByComparingTo("50.00");
        assertThat(result.get(3).price()).isEqualByComparingTo("47.62");
    }

    @Test
    @DisplayName("buildCappedBuyOrders 후반 1건, 캡 후 수량 0: 매수+보정 모두 제외 = 빈 리스트")
    void buildCappedBuyOrders_singleBuy_cappedQuantityZero_returnsEmpty() {
        // cap=55, K=50(usdDeposit=1000) / qty = floor(50/55) = 0 → 매수 제외, 누적수량 0이므로 보정도 제외
        InfinitePosition position = positionWithUnitAmount("1000");
        List<Order> buyOrders = List.of(buy("200.00", 1));

        List<Order> result = strategy.buildCappedBuyOrders(position, TODAY, buyOrders, new BigDecimal("55.00"));

        assertThat(result).isEmpty();
    }
}
