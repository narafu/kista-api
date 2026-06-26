package com.kista.domain.model;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
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
}
