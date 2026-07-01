package com.kista.domain.model;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AccountBalance.applyExecutions — 평단가 계산")
class AccountBalanceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 1, 1);
    private static final Ticker TICKER = Ticker.SOXL;

    private static Execution buy(int qty, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Execution(DATE, TICKER, Order.OrderDirection.BUY, qty, p,
                p.multiply(BigDecimal.valueOf(qty)), null);
    }

    private static Execution sell(int qty, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Execution(DATE, TICKER, Order.OrderDirection.SELL, qty, p,
                p.multiply(BigDecimal.valueOf(qty)), null);
    }

    @Test
    @DisplayName("빈 체결 목록 — 잔고 그대로 반환")
    void emptyExecutions_returnsUnchanged() {
        AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("5000"));
        AccountBalance result = balance.applyExecutions(List.of());
        assertThat(result).isEqualTo(balance);
    }

    @Test
    @DisplayName("순수 매수 — 평단가 가중평균 계산")
    void pureBuy_weightsAvgPrice() {
        // holdings=100 @ $25, 추가 매수 50주 @ $30
        AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("10000"));
        AccountBalance result = balance.applyExecutions(List.of(buy(50, "30.00")));

        // (100*25 + 50*30) / 150 = 4000 / 150 = 26.6667
        assertThat(result.holdings()).isEqualTo(150);
        assertThat(result.avgPrice()).isEqualByComparingTo("26.6667");
    }

    @Test
    @DisplayName("부분 매도 — 평단가 불변, 보유수량만 감소")
    void partialSell_avgPriceUnchanged() {
        // holdings=200 @ $25, 100주 매도 — 평단가 $25 유지
        AccountBalance balance = new AccountBalance(200, new BigDecimal("25.00"), new BigDecimal("1000"));
        AccountBalance result = balance.applyExecutions(List.of(sell(100, "30.00")));

        assertThat(result.holdings()).isEqualTo(100);
        assertThat(result.avgPrice()).isEqualByComparingTo("25.0000");
    }

    @Test
    @DisplayName("전량 매도 — 평단가 null")
    void fullSell_avgPriceNull() {
        AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("1000"));
        AccountBalance result = balance.applyExecutions(List.of(sell(100, "30.00")));

        assertThat(result.holdings()).isZero();
        assertThat(result.avgPrice()).isNull();
    }

    @Test
    @DisplayName("매도 후 추가 매수 — 잔여 수량 기준 재가중평균")
    void sellThenBuySameDay_correctAvg() {
        // holdings=100 @ $25, 매도 50 + 매수 30 @ $30
        // 매도 후: 50주 @ $25 (cost=$1250)
        // 매수 후: (1250 + 30*30) / 80 = (1250+900)/80 = 26.875
        AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("5000"));
        AccountBalance result = balance.applyExecutions(List.of(sell(50, "30.00"), buy(30, "30.00")));

        assertThat(result.holdings()).isEqualTo(80);
        assertThat(result.avgPrice()).isEqualByComparingTo("26.8750");
    }

    @Test
    @DisplayName("holdings=0에서 매수 시작 — avgPrice = 매수 단가")
    void startFromZero_avgPriceIsFirstBuy() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        AccountBalance result = balance.applyExecutions(List.of(buy(100, "25.00")));

        assertThat(result.holdings()).isEqualTo(100);
        assertThat(result.avgPrice()).isEqualByComparingTo("25.0000");
    }

    @Nested
    @DisplayName("AccountBalance.hasSufficientDepositFor — 수동 실행 예수금 검증")
    class HasSufficientDepositForTest {

        // 전략 계산용 PLANNED BUY 주문 헬퍼
        private Order buyOrder(int qty, String price) {
            return Order.planned(DATE, TICKER, Order.OrderType.LOC, Order.OrderDirection.BUY,
                    qty, new BigDecimal(price));
        }

        // SELL 주문 헬퍼 (BUY 없는 케이스 검증용)
        private Order sellOrder(int qty, String price) {
            return Order.planned(DATE, TICKER, Order.OrderType.LOC, Order.OrderDirection.SELL,
                    qty, new BigDecimal(price));
        }

        @Test
        @DisplayName("BUY 주문 없으면 무조건 통과 — SELL만 있는 경우")
        void noBuyOrders_alwaysTrue() {
            // SELL만 있어도 newBuyTotal = 0 → 조기 true 반환
            AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("1000"));
            List<Order> orders = List.of(sellOrder(50, "30.00"));
            assertThat(balance.hasSufficientDepositFor(orders, BigDecimal.ZERO)).isTrue();
        }

        @Test
        @DisplayName("BUY 합계 = available (정확히 같을 때) — 통과")
        void buyTotalEqualsAvailable_returnsTrue() {
            // usdDeposit=1000, otherTotal=200 → available=800
            // BUY 2주 @ $400 → newBuyTotal=800 = available → true
            AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
            List<Order> orders = List.of(buyOrder(2, "400.00"));
            assertThat(balance.hasSufficientDepositFor(orders, new BigDecimal("200"))).isTrue();
        }

        @Test
        @DisplayName("BUY 합계 < available — 통과")
        void buyTotalBelowAvailable_returnsTrue() {
            // usdDeposit=1000, otherTotal=0 → available=1000
            // BUY 2주 @ $300 → newBuyTotal=600 < available=1000 → true
            AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
            List<Order> orders = List.of(buyOrder(2, "300.00"));
            assertThat(balance.hasSufficientDepositFor(orders, BigDecimal.ZERO)).isTrue();
        }

        @Test
        @DisplayName("otherTotal 차감 없이도 BUY 합계가 예수금 초과 — false")
        void buyExceedsDepositWithNoOtherTotal_returnsFalse() {
            // usdDeposit=500, otherTotal=0 → available=500
            // BUY 2주 @ $300 → newBuyTotal=600 > available=500 → false
            AccountBalance balance = new AccountBalance(0, null, new BigDecimal("500"));
            List<Order> orders = List.of(buyOrder(2, "300.00"));
            assertThat(balance.hasSufficientDepositFor(orders, BigDecimal.ZERO)).isFalse();
        }

        @Test
        @DisplayName("BUY 합계 < 예수금이지만 타 전략 차감 후 부족 — false")
        void buyPassesAloneButFailsAfterOtherTotalDeducted_returnsFalse() {
            // usdDeposit=1000, otherTotal=500 → available=500
            // BUY 2주 @ $300 → newBuyTotal=600 < usdDeposit(1000)이지만 > available(500) → false
            AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
            List<Order> orders = List.of(buyOrder(2, "300.00"));
            assertThat(balance.hasSufficientDepositFor(orders, new BigDecimal("500"))).isFalse();
        }
    }
}
