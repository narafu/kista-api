package com.kista.domain.strategy;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderStatus.PLACED;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrectionStrategy 잔여 unitAmount 보정 매수 검증")
class CorrectionStrategyTest {

    private final CorrectionStrategy strategy = new CorrectionStrategy();
    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);

    // unitAmount = (usdDeposit + avgPrice×holdings) / 20
    // holdings=10, avgPrice=20 → purchaseAmount=200, totalAssets=1200, unitAmount=60
    private static final AccountBalance BALANCE = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
    // SOXL targetProfitRate=0.20
    private static final InfinitePosition POSITION = new InfinitePosition(BALANCE, Ticker.SOXL, new BigDecimal("22.00"));
    // unitAmount = (1000 + 200) / 20 = 60.00

    private Execution buyExec(String amount) {
        return new Execution(TODAY, Ticker.SOXL, BUY, 1,
                new BigDecimal(amount), new BigDecimal(amount), "ORD-BUY");
    }

    private Execution sellExec(String amount) {
        return new Execution(TODAY, Ticker.SOXL, SELL, 1,
                new BigDecimal(amount), new BigDecimal(amount), "ORD-SELL");
    }

    @Test
    @DisplayName("체결 없음 → unitAmount 전체를 종가로 매수: floor(60 / 22) = 2주")
    void correct_noExecutions_buyFullUnitAmount() {
        List<Order> result = strategy.correct(POSITION, new BigDecimal("22.00"), List.of(), TODAY);

        assertThat(result).hasSize(1);
        Order order = result.getFirst();
        assertThat(order.direction()).isEqualTo(BUY);
        assertThat(order.orderType()).isEqualTo(LIMIT);
        assertThat(order.quantity()).isEqualTo(2); // floor(60 / 22) = 2
        assertThat(order.price()).isEqualByComparingTo("22.00");
        assertThat(order.tradeDate()).isEqualTo(TODAY);
        assertThat(order.status()).isEqualTo(PLACED);
        assertThat(order.ticker()).isEqualTo(Ticker.SOXL);
    }

    @Test
    @DisplayName("BUY 체결 일부 → 잔여 여력만 매수: floor((60 - 20) / 22) = 1주")
    void correct_partialBuyFilled_buyRemaining() {
        List<Order> result = strategy.correct(POSITION, new BigDecimal("22.00"),
                List.of(buyExec("20.00")), TODAY);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantity()).isEqualTo(1); // floor(40 / 22) = 1
    }

    @Test
    @DisplayName("BUY 체결 합계 ≥ unitAmount → 여력 없음, 보정 주문 없음")
    void correct_buyFilledExceedsUnitAmount_noCorrection() {
        List<Order> result = strategy.correct(POSITION, new BigDecimal("22.00"),
                List.of(buyExec("60.00")), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("SELL만 체결 → 매도는 차감 대상 아님, unitAmount 전체 매수")
    void correct_onlySellFilled_buyFullUnitAmount() {
        List<Order> result = strategy.correct(POSITION, new BigDecimal("22.00"),
                List.of(sellExec("100.00")), TODAY);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantity()).isEqualTo(2); // SELL은 무시, floor(60 / 22) = 2
    }

    @Test
    @DisplayName("종가가 비싸 수량 0 → 보정 주문 없음")
    void correct_closingPriceToHighForAnyShare_noCorrection() {
        // unitAmount=60, closingPrice=100 → floor(60/100) = 0
        List<Order> result = strategy.correct(POSITION, new BigDecimal("100.00"), List.of(), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("BUY + SELL 혼합 체결 → BUY만 차감, SELL 무시")
    void correct_mixedExecutions_onlyBuyDeducted() {
        // BUY 30, SELL 50 → remaining = 60 - 30 = 30 → floor(30 / 22) = 1
        List<Order> result = strategy.correct(POSITION, new BigDecimal("22.00"),
                List.of(buyExec("30.00"), sellExec("50.00")), TODAY);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantity()).isEqualTo(1); // floor(30 / 22) = 1
    }
}
