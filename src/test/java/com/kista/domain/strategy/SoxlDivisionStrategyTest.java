package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.Order;
import com.kista.domain.model.TradingVariables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.Order.OrderDirection.BUY;
import static com.kista.domain.model.Order.OrderDirection.SELL;
import static com.kista.domain.model.Order.OrderType.LIMIT;
import static com.kista.domain.model.Order.OrderType.LOC;
import static com.kista.domain.model.Order.OrderType.MOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SoxlDivisionStrategy 매매 변수 계산 검증")
class SoxlDivisionStrategyTest {

    private final SoxlDivisionStrategy strategy = new SoxlDivisionStrategy();
    private static final LocalDate TODAY = LocalDate.of(2025, 1, 15);

    @Test
    @DisplayName("Q=0: 잔고 없을 때 currentPrice를 A로 사용, currentRound=0, priceOffsetRate=0.2000")
    void case_q0() {
        // A=20, M=0, D=0, B=2000, K=100.00, T=0.0, priceOffsetRate=0.2000, targetPrice=24.00
        AccountBalance balance = new AccountBalance(0, null,
                new BigDecimal("0"),     // effectiveAmt: SOXL 없으므로 0
                new BigDecimal("2000")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("20"));

        assertThat(vars.averagePrice()).isEqualByComparingTo("20");
        assertThat(vars.quantity()).isEqualTo(0);
        assertThat(vars.purchaseAmount()).isEqualByComparingTo("0");
        assertThat(vars.evaluationAmount()).isEqualByComparingTo("0");
        assertThat(vars.totalAssets()).isEqualByComparingTo("2000");
        assertThat(vars.totalRounds()).isEqualTo(20);
        assertThat(vars.currentRound()).isEqualTo(0.0);
        assertThat(vars.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(vars.targetProfitRate()).isEqualByComparingTo("0.20");
        assertThat(vars.priceOffsetRate()).isEqualByComparingTo("0.2000");
        assertThat(vars.usdDeposit()).isEqualByComparingTo("2000");
        assertThat(vars.referencePrice()).isEqualByComparingTo("24.00"); // 20 × (1+0.2000)
        assertThat(vars.targetPrice()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("Q=10: currentRound=1.33, priceOffsetRate=0.1733")
    void case_q10() {
        // A=20, M=200, D=210, B=3000, K=150.00, T=200/150=1.33
        // priceOffsetRate = 0.20*(1 - 2*1.33/20) = 0.1733
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"),
                new BigDecimal("210"),   // effectiveAmt: 21×10 (SOXL 시가)
                new BigDecimal("2800")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("21"));

        assertThat(vars.averagePrice()).isEqualByComparingTo("20");
        assertThat(vars.quantity()).isEqualTo(10);
        assertThat(vars.purchaseAmount()).isEqualByComparingTo("200");
        assertThat(vars.evaluationAmount()).isEqualByComparingTo("210");
        assertThat(vars.totalAssets()).isEqualByComparingTo("3000");
        assertThat(vars.totalRounds()).isEqualTo(20);
        assertThat(vars.currentRound()).isCloseTo(1.33, within(0.01));
        assertThat(vars.unitAmount()).isEqualByComparingTo("150.00");
        assertThat(vars.targetProfitRate()).isEqualByComparingTo("0.20");
        assertThat(vars.priceOffsetRate()).isEqualByComparingTo("0.1734");
        assertThat(vars.usdDeposit()).isEqualByComparingTo("2800");
        assertThat(vars.referencePrice()).isEqualByComparingTo("23.47"); // 20 × (1+0.1734) = 23.468 → 23.47
        assertThat(vars.targetPrice()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("Q=100: currentRound=5.0 (정확히 5회차), priceOffsetRate=0.1000")
    void case_q100() {
        // A=5, M=500, D=600, B=2000, K=100.00, T=500/100=5.0
        // priceOffsetRate = 0.20*(1 - 10/20) = 0.1000
        AccountBalance balance = new AccountBalance(100, new BigDecimal("5"),
                new BigDecimal("600"),   // effectiveAmt: 6×100 (SOXL 시가)
                new BigDecimal("1500")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("6"));

        assertThat(vars.averagePrice()).isEqualByComparingTo("5");
        assertThat(vars.quantity()).isEqualTo(100);
        assertThat(vars.purchaseAmount()).isEqualByComparingTo("500");
        assertThat(vars.evaluationAmount()).isEqualByComparingTo("600");
        assertThat(vars.totalAssets()).isEqualByComparingTo("2000");
        assertThat(vars.totalRounds()).isEqualTo(20);
        assertThat(vars.currentRound()).isEqualTo(5.0);
        assertThat(vars.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(vars.targetProfitRate()).isEqualByComparingTo("0.20");
        assertThat(vars.priceOffsetRate()).isEqualByComparingTo("0.1000");
        assertThat(vars.usdDeposit()).isEqualByComparingTo("1500");
        assertThat(vars.referencePrice()).isEqualByComparingTo("5.50"); // 5 × (1+0.1000)
        assertThat(vars.targetPrice()).isEqualByComparingTo("6.00");
    }

    // ── buildOrders() 시나리오 ──────────────────────────────────────────────

    @Test
    @DisplayName("buildOrders 전반 Q>0: LOC매수①② + LOC매도 + 지정가매도 = 4건")
    void buildOrders_frontHalf_withQty() {
        // A=20, Q=10, D=1000 → B=1200, K=60, T≈3.33, S>0 (전반)
        // G=20×(1+S)≈22.67, P=24.00
        // BUY①: floor(60/2/20)=1, BUY②: floor(60/2/22.67)=1
        // LOC SELL: 10/4=2, LIMIT SELL: 10-2=8
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"),
                new BigDecimal("220"), new BigDecimal("1000"));
        TradingVariables vars = strategy.calculate(balance, new BigDecimal("22"));

        List<Order> orders = strategy.buildOrders(vars, TODAY, "SOXL");

        assertThat(orders).hasSize(4);
        assertThat(orders.get(0)).matches(o -> o.orderType() == LOC && o.direction() == BUY && o.price().compareTo(vars.averagePrice()) == 0);
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == BUY && o.price().compareTo(vars.referencePrice()) == 0);
        assertThat(orders.get(2)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.qty() == 2);
        assertThat(orders.get(3)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.qty() == 8 && o.price().compareTo(vars.targetPrice()) == 0);
        assertThat(orders).allMatch(o -> o.symbol().equals("SOXL") && o.tradeDate().equals(TODAY));
    }

    @Test
    @DisplayName("buildOrders 전반 Q=0: BUY①만 (BUY②<1주, SELL Q/4=0)")
    void buildOrders_frontHalf_noQty() {
        // A=currentPrice=22, Q=0, D=1000 → B=1000, K=50
        // G=22×1.20=26.40, BUY①: floor(50/2/22)=1, BUY②: floor(50/2/26.40)=0
        AccountBalance balance = new AccountBalance(0, null,
                new BigDecimal("0"), new BigDecimal("1000"));

        List<Order> orders = strategy.buildOrders(strategy.calculate(balance, new BigDecimal("22")), TODAY, "SOXL");

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0)).matches(o -> o.orderType() == LOC && o.direction() == BUY);
    }

    @Test
    @DisplayName("buildOrders 후반 K>D: MOC 매도 1건만")
    void buildOrders_backHalf_insufficientDeposit() {
        // A=5, Q=200, D=50 → B=1050, K=52.50, T≈19.05 (후반), K(52.50)>D(50)
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"),
                new BigDecimal("1000"), new BigDecimal("50"));

        List<Order> orders = strategy.buildOrders(strategy.calculate(balance, new BigDecimal("5")), TODAY, "SOXL");

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0)).matches(o -> o.orderType() == MOC && o.direction() == SELL && o.qty() == 50); // 200/4
    }

    @Test
    @DisplayName("buildOrders 후반 K<=D: LOC매수 + LOC매도 + 지정가매도 = 3건")
    void buildOrders_backHalf_sufficientDeposit() {
        // A=5, Q=200, D=100 → B=1100, K=55, T≈18.18 (후반), K(55)<=D(100)
        AccountBalance balance = new AccountBalance(200, new BigDecimal("5"),
                new BigDecimal("1000"), new BigDecimal("100"));

        List<Order> orders = strategy.buildOrders(strategy.calculate(balance, new BigDecimal("5")), TODAY, "SOXL");

        assertThat(orders).hasSize(3);
        assertThat(orders.get(0)).matches(o -> o.orderType() == LOC && o.direction() == BUY);
        assertThat(orders.get(1)).matches(o -> o.orderType() == LOC && o.direction() == SELL && o.qty() == 50); // 200/4
        assertThat(orders.get(2)).matches(o -> o.orderType() == LIMIT && o.direction() == SELL && o.qty() == 150); // 200-50
    }
}
