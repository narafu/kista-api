package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeBase.PrivacyTrade;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderType.LOC;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrivacyStrategy.buildOrders — 보유 잔량 기준 BUY 가감 / SELL null 잔량 처리")
class PrivacyStrategyTest {

    private static final PrivacyStrategy strategy = new PrivacyStrategy();
    private static final BigDecimal BASE_CYCLE_START = new BigDecimal("1000"); // 기준 사이클 시작가
    private static final BigDecimal INITIAL_USD_DEPOSIT = new BigDecimal("1000"); // 1배수: 1000/1000=1.00
    private static final LocalDate DATE = LocalDate.of(2026, 5, 26);
    private static final Ticker TICKER = Ticker.SOXL;

    // 계좌잔고 헬퍼
    private static AccountBalance balance(int holdings) {
        return new AccountBalance(holdings, new BigDecimal("30.00"), new BigDecimal("1000"));
    }

    // 기준 매매표 헬퍼 — currentCycleStart=1000 고정 (INITIAL_USD_DEPOSIT/BASE_CYCLE_START=1.00)
    private static PrivacyTradeBase base(int holdings, List<PrivacyTrade> trades) {
        return new PrivacyTradeBase(UUID.randomUUID(), new BigDecimal("30.00"), holdings, BASE_CYCLE_START, trades);
    }

    private static PrivacyTrade buy(int quantity, String price) {
        return new PrivacyTrade(DATE, TICKER, LOC, BUY, quantity, new BigDecimal(price));
    }

    private static PrivacyTrade sell(int quantity, String price) {
        return new PrivacyTrade(DATE, TICKER, LIMIT, SELL, quantity, new BigDecimal(price));
    }

    private static PrivacyTrade sellNull(String price) {
        return new PrivacyTrade(DATE, TICKER, LIMIT, SELL, null, new BigDecimal(price));
    }

    @Test
    @DisplayName("정확 일치 — diff=0 이면 BUY/SELL 모두 multiple 적용 그대로 반환")
    void exactMatch() {
        // 기준표 holdings=240, balance=240 → diff=0
        PrivacyTradeBase base = base(240, List.of(buy(100, "10"), buy(80, "9"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(240), INITIAL_USD_DEPOSIT, base);

        assertThat(orders).hasSize(3);
        assertThat(buyOrders(orders)).extracting(Order::quantity).containsExactlyInAnyOrder(100, 80);
        assertThat(sellOrders(orders)).extracting(Order::quantity).containsExactly(50);
    }

    @Test
    @DisplayName("증액 — 보유 부족 시 가장 싼 BUY에 부족분 전량 추가")
    void increaseOnShortfall() {
        // target=240, current=200, diff=+40 → 가장 싼 8$ BUY(60→100주)
        PrivacyTradeBase base = base(240, List.of(buy(100, "10"), buy(80, "9"), buy(60, "8"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(200), INITIAL_USD_DEPOSIT, base);

        assertThat(buyOrders(orders)).hasSize(3);
        Order cheapestBuy = buyOrders(orders).stream()
                .filter(o -> o.price().compareTo(new BigDecimal("8")) == 0)
                .findFirst().orElseThrow();
        assertThat(cheapestBuy.quantity()).isEqualTo(100); // 60 + 40
        // 나머지 BUY는 그대로
        assertThat(buyOrders(orders)).extracting(Order::quantity).contains(100, 80);
        assertThat(sellOrders(orders)).extracting(Order::quantity).containsExactly(50);
    }

    @Test
    @DisplayName("감액 단일 차감 — 초과분이 가장 비싼 BUY 1건 내 — 해당 건만 차감")
    void decreasePartialSingleEntry() {
        // target=240, current=270, diff=-30 → 가장 비싼 10$ BUY(100→70주)
        PrivacyTradeBase base = base(240, List.of(buy(100, "10"), buy(80, "9"), buy(60, "8"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(270), INITIAL_USD_DEPOSIT, base);

        Order mostExpensiveBuy = buyOrders(orders).stream()
                .filter(o -> o.price().compareTo(new BigDecimal("10")) == 0)
                .findFirst().orElseThrow();
        assertThat(mostExpensiveBuy.quantity()).isEqualTo(70); // 100 - 30
        assertThat(buyOrders(orders)).extracting(Order::quantity).contains(80, 60);
    }

    @Test
    @DisplayName("감액 이월 — 초과분 > 가장 비싼 BUY → 제거 후 다음 비싼 BUY로 이월 차감")
    void decreaseWithCarryover() {
        // target=240, current=370, diff=-130
        // 10$BUY(100주) 전량 차감 후 remaining=30 → 9$BUY(80→50주)
        PrivacyTradeBase base = base(240, List.of(buy(100, "10"), buy(80, "9"), buy(60, "8"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(370), INITIAL_USD_DEPOSIT, base);

        // 10$BUY는 quantity=0이므로 결과에서 제외
        assertThat(buyOrders(orders)).noneMatch(o -> o.price().compareTo(new BigDecimal("10")) == 0);
        Order secondBuy = buyOrders(orders).stream()
                .filter(o -> o.price().compareTo(new BigDecimal("9")) == 0)
                .findFirst().orElseThrow();
        assertThat(secondBuy.quantity()).isEqualTo(50); // 80 - 30
        assertThat(buyOrders(orders)).extracting(Order::quantity).contains(60); // 8$는 그대로
    }

    @Test
    @DisplayName("감액 BUY 모두 소진 — 초과분 > BUY 합 → BUY 전부 제거, SELL만 반환")
    void decreaseExceedsAllBuys() {
        // target=0, current=250 → diff=-250, BUY 합=240 → 모두 0 후 잔여 10 무시
        PrivacyTradeBase base = base(0, List.of(buy(100, "10"), buy(80, "9"), buy(60, "8"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(250), INITIAL_USD_DEPOSIT, base);

        assertThat(buyOrders(orders)).isEmpty();
        assertThat(sellOrders(orders)).hasSize(1);
    }

    @Test
    @DisplayName("BUY 0개 케이스 — SELL만 있을 때 diff 무관하게 SELL만 반환")
    void noBuyTrades() {
        // diff != 0 이지만 BUY 후보 없음
        PrivacyTradeBase base = base(240, List.of(sell(50, "12"), sell(30, "13")));
        List<Order> orders = strategy.buildOrders(balance(100), INITIAL_USD_DEPOSIT, base);

        assertThat(buyOrders(orders)).isEmpty();
        assertThat(sellOrders(orders)).hasSize(2);
    }

    @Test
    @DisplayName("quantity null trade — 필터링되어 결과에 미포함")
    void nullQuantityFiltered() {
        PrivacyTrade nullQuantity = new PrivacyTrade(DATE, TICKER, LOC, BUY, null, new BigDecimal("10"));
        PrivacyTradeBase base = base(100, List.of(nullQuantity, buy(80, "9"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(100), INITIAL_USD_DEPOSIT, base);

        // null quantity BUY는 제외, 유효한 BUY(9$)와 SELL(12$)만 포함
        assertThat(orders).hasSize(2);
        assertThat(buyOrders(orders)).hasSize(1);
        assertThat(buyOrders(orders).getFirst().price()).isEqualTo(new BigDecimal("9"));
    }

    // ── 잔여 매수금 배분 (allocateRemainingBudget) ───────────────────────────

    @Test
    @DisplayName("잔여 매수금 배분 — 배수 0.5 적용 후 버림 잔여분이 최저가 BUY에 추가")
    void remainingBudgetAllocatedToLowestPrice() {
        // BUY: $298.8×3, $279.02×3 → 총 매수금 1733.46, 배수 0.5 → 목표 866.73
        // 배수 적용: floor(3×0.5)=1주씩 → 사용금 577.82, 잔여 288.91
        // floor(288.91 / 279.02) = 1 → 최저가(279.02) 엔트리에 +1 = 2주
        BigDecimal initialUsdDeposit = new BigDecimal("500"); // 500/1000 = 0.50
        PrivacyTradeBase base = base(6, List.of(
                buy(3, "298.8"),
                buy(3, "279.02")
        ));
        // target = floor(6 × 0.5) = 3, balance=3 → diff=0
        List<Order> orders = strategy.buildOrders(balance(3), initialUsdDeposit, base);

        List<Order> buys = buyOrders(orders);
        assertThat(buys).hasSize(2);
        Order highBuy = buys.stream().filter(o -> o.price().compareTo(new BigDecimal("298.8")) == 0).findFirst().orElseThrow();
        Order lowBuy  = buys.stream().filter(o -> o.price().compareTo(new BigDecimal("279.02")) == 0).findFirst().orElseThrow();
        assertThat(highBuy.quantity()).isEqualTo(1);
        assertThat(lowBuy.quantity()).isEqualTo(2); // 1 + 잔여 1
    }

    @Test
    @DisplayName("잔여 매수금 배분 — 잔여금이 최저가보다 작으면 추가 수량 없음")
    void remainingBudgetTooSmallForAdditionalShare() {
        // BUY: $500×1 → 총 매수금 500, 배수 0.3 → 목표 150
        // 배수 적용: floor(1×0.3)=0주 → 사용금 0, 잔여 150
        // floor(150 / 500) = 0 → 추가 없음 (quantity=0이므로 필터링되어 BUY 결과 없음)
        BigDecimal initialUsdDeposit = new BigDecimal("300"); // 300/1000 = 0.30
        PrivacyTradeBase base = base(1, List.of(buy(1, "500")));
        // target = floor(1×0.3) = 0, balance=0 → diff=0
        List<Order> orders = strategy.buildOrders(balance(0), initialUsdDeposit, base);

        assertThat(buyOrders(orders)).isEmpty();
    }

    @Test
    @DisplayName("잔여 매수금 배분 — 배수가 정수이면 잔여금 0, 추가 배분 없음")
    void noRemainingBudgetWhenMultipleIsExact() {
        // BUY: $10×100, $9×80, multiple=1.00 → 잔여금 0
        PrivacyTradeBase base = base(240, List.of(buy(100, "10"), buy(80, "9"), sell(50, "12")));
        List<Order> orders = strategy.buildOrders(balance(240), INITIAL_USD_DEPOSIT, base);

        // 기존 수량 그대로 — 잔여 배분 없음
        assertThat(buyOrders(orders)).extracting(Order::quantity).containsExactlyInAnyOrder(100, 80);
    }

    @Test
    @DisplayName("multiple 적용 — initialUsdDeposit/currentCycleStart=1.5 → BUY/SELL 수량 1.5배 반영 (소수점 버림)")
    void multipleApplied() {
        // initialUsdDeposit=1500, currentCycleStart=1000 → multiple=1.50
        // 100주→150주, 80주→120주, sell 50주→75주
        BigDecimal initialUsdDeposit = new BigDecimal("1500"); // 1500/1000 = 1.50
        PrivacyTradeBase base = base(200, List.of(buy(100, "10"), buy(80, "9"), sell(50, "12")));
        // balance=300(=200*1.5), target=300 → diff=0
        List<Order> orders = strategy.buildOrders(balance(300), initialUsdDeposit, base);

        assertThat(buyOrders(orders)).extracting(Order::quantity).containsExactlyInAnyOrder(150, 120);
        assertThat(sellOrders(orders)).extracting(Order::quantity).containsExactly(75);
    }

    // ── SELL null quantity = "잔량 전부 매도" ───────────────────────────────

    @Test
    @DisplayName("null SELL + 명시 SELL 다수 — 잔량에서 명시 SELL 합 차감 후 null SELL 수량 결정")
    void nullSellWithMultipleExplicit() {
        // balance=70, SELL A=23, B=22, C=null → C = 70 - 23 - 22 = 25
        PrivacyTradeBase base = base(100, List.of(sell(23, "12"), sell(22, "13"), sellNull("14")));
        List<Order> orders = strategy.buildOrders(balance(70), INITIAL_USD_DEPOSIT, base);

        assertThat(sellOrders(orders)).hasSize(3);
        assertThat(sellOrders(orders)).extracting(Order::quantity).containsExactlyInAnyOrder(23, 22, 25);
    }

    @Test
    @DisplayName("null SELL + 명시 SELL 1건 — 잔량에서 명시 SELL 차감")
    void nullSellWithOneExplicit() {
        // balance=50, SELL A=null, B=12 → A = 50 - 12 = 38
        PrivacyTradeBase base = base(100, List.of(sellNull("13"), sell(12, "14")));
        List<Order> orders = strategy.buildOrders(balance(50), INITIAL_USD_DEPOSIT, base);

        assertThat(sellOrders(orders)).hasSize(2);
        assertThat(sellOrders(orders)).extracting(Order::quantity).containsExactlyInAnyOrder(38, 12);
    }

    @Test
    @DisplayName("null SELL만 있음 — 잔량 전체가 null SELL 수량")
    void nullSellOnly() {
        // balance=100, SELL [null] → remaining = 100
        PrivacyTradeBase base = base(100, List.of(sellNull("12")));
        List<Order> orders = strategy.buildOrders(balance(100), INITIAL_USD_DEPOSIT, base);

        assertThat(sellOrders(orders)).hasSize(1);
        assertThat(sellOrders(orders).getFirst().quantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("명시 SELL 합 = balance — null SELL remaining=0 → null SELL 제외")
    void nullSellExcludedWhenRemainingZero() {
        // balance=50, SELL [50, null] → remaining=0 → null SELL 제외
        PrivacyTradeBase base = base(100, List.of(sell(50, "12"), sellNull("13")));
        List<Order> orders = strategy.buildOrders(balance(50), INITIAL_USD_DEPOSIT, base);

        assertThat(sellOrders(orders)).hasSize(1);
        assertThat(sellOrders(orders).getFirst().quantity()).isEqualTo(50);
    }

    @Test
    @DisplayName("명시 SELL 합 > balance — null SELL remaining 음수 → null SELL 제외")
    void nullSellExcludedWhenRemainingNegative() {
        // balance=50, SELL [70, null] → remaining=-20 → null SELL 제외
        PrivacyTradeBase base = base(100, List.of(sell(70, "12"), sellNull("13")));
        List<Order> orders = strategy.buildOrders(balance(50), INITIAL_USD_DEPOSIT, base);

        assertThat(sellOrders(orders)).hasSize(1);
        assertThat(sellOrders(orders).getFirst().quantity()).isEqualTo(70);
    }

    @Test
    @DisplayName("null SELL 2개 — IllegalStateException 발생")
    void nullSellTwiceThrows() {
        PrivacyTradeBase base = base(100, List.of(sellNull("12"), sellNull("13")));
        assertThatThrownBy(() -> strategy.buildOrders(balance(100), INITIAL_USD_DEPOSIT, base))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELL null quantity는 1개만 허용");
    }

    @Test
    @DisplayName("multiple 적용 — null SELL은 절대 잔량 기준, 명시 SELL은 multiple 적용")
    void nullSellMultipleApplied() {
        // initialUsdDeposit=1500, currentCycleStart=1000 → multiple=1.50
        // 명시 SELL: 10 × 1.5 = 15, null SELL: 30 - 15 = 15
        BigDecimal initialUsdDeposit = new BigDecimal("1500");
        PrivacyTradeBase base = base(100, List.of(sell(10, "12"), sellNull("13")));
        List<Order> orders = strategy.buildOrders(balance(30), initialUsdDeposit, base);

        assertThat(sellOrders(orders)).hasSize(2);
        assertThat(sellOrders(orders)).extracting(Order::quantity).containsExactlyInAnyOrder(15, 15);
    }

    // 헬퍼
    private static List<Order> buyOrders(List<Order> orders) {
        return orders.stream().filter(o -> o.direction() == BUY).toList();
    }

    private static List<Order> sellOrders(List<Order> orders) {
        return orders.stream().filter(o -> o.direction() == SELL).toList();
    }
}
