package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.OrderDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AccountBalance.applyExecutions — 평단가 계산")
class AccountBalanceTest {


    // matching은 broker를 참조하지 않는다 — Fill을 익명 클래스로 직접 구현(broker Execution 미경유)
    private static AccountBalance.Fill buy(int qty, String price) {
        return fill(OrderDirection.BUY, qty, price);
    }

    private static AccountBalance.Fill sell(int qty, String price) {
        return fill(OrderDirection.SELL, qty, price);
    }

    private static AccountBalance.Fill fill(OrderDirection direction, int qty, String price) {
        return fill(direction, String.valueOf(qty), price);
    }

    // 체결금액을 직접 지정(price × qty가 아닌 값)
    private static AccountBalance.Fill fillWithAmount(OrderDirection direction, String qty, String amountText) {
        BigDecimal quantity = new BigDecimal(qty);
        BigDecimal amount = new BigDecimal(amountText);
        return new AccountBalance.Fill() {
            @Override public OrderDirection direction() { return direction; }
            @Override public BigDecimal quantity() { return quantity; }
            @Override public BigDecimal amount() { return amount; }
        };
    }

    private static AccountBalance.Fill fill(OrderDirection direction, String qty, String price) {
        BigDecimal quantity = new BigDecimal(qty);
        BigDecimal amount = new BigDecimal(price).multiply(quantity);
        return new AccountBalance.Fill() {
            @Override public OrderDirection direction() { return direction; }
            @Override public BigDecimal quantity() { return quantity; }
            @Override public BigDecimal amount() { return amount; }
        };
    }

    @Test
    @DisplayName("평단가 경계 — 원값 45.12495는 scale 2 HALF_UP으로 바로 45.12 (4자리 선반올림 45.1250 경유 금지)")
    void avgPrice_roundsDirectlyToScale2() {
        // (199×45.12 + 1×46.11) / 200 = 9024.99 / 200 = 45.12495
        AccountBalance balance = new AccountBalance(199, new BigDecimal("45.12"), new BigDecimal("1000"));
        AccountBalance result = balance.applyExecutions(List.of(buy(1, "46.11")));
        assertThat(result.avgPrice()).isEqualTo(new BigDecimal("45.12"));
        // 정상 케이스: (10×20 + 10×30) / 20 = 25 → 25.00
        assertThat(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000"))
                .applyExecutions(List.of(buy(10, "30.00"))).avgPrice()).isEqualTo(new BigDecimal("25.00"));
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

        // (100*25 + 50*30) / 150 = 4000 / 150 = 26.666… → 26.67 (scale 2 HALF_UP)
        assertThat(result.holdings()).isEqualByComparingTo("150");
        assertThat(result.avgPrice()).isEqualByComparingTo("26.67");
    }

    @Test
    @DisplayName("부분 매도 — 평단가 불변, 보유수량만 감소")
    void partialSell_avgPriceUnchanged() {
        // holdings=200 @ $25, 100주 매도 — 평단가 $25 유지
        AccountBalance balance = new AccountBalance(200, new BigDecimal("25.00"), new BigDecimal("1000"));
        AccountBalance result = balance.applyExecutions(List.of(sell(100, "30.00")));

        assertThat(result.holdings()).isEqualByComparingTo("100");
        assertThat(result.avgPrice()).isEqualByComparingTo("25.0000");
    }

    @Test
    @DisplayName("전량 매도 — 평단가 null")
    void fullSell_avgPriceNull() {
        AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("1000"));
        AccountBalance result = balance.applyExecutions(List.of(sell(100, "30.00")));

        assertThat(result.holdings().signum()).isZero();
        assertThat(result.avgPrice()).isNull();
    }

    @Test
    @DisplayName("매도 후 추가 매수 — 잔여 수량 기준 재가중평균")
    void sellThenBuySameDay_correctAvg() {
        // holdings=100 @ $25, 매도 50 + 매수 30 @ $30
        // 매도 후: 50주 @ $25 (cost=$1250)
        // 매수 후: (1250 + 30*30) / 80 = (1250+900)/80 = 26.875 → 26.88 (scale 2 HALF_UP)
        AccountBalance balance = new AccountBalance(100, new BigDecimal("25.00"), new BigDecimal("5000"));
        AccountBalance result = balance.applyExecutions(List.of(sell(50, "30.00"), buy(30, "30.00")));

        assertThat(result.holdings()).isEqualByComparingTo("80");
        assertThat(result.avgPrice()).isEqualByComparingTo("26.88");
    }

    @Test
    @DisplayName("holdings=0에서 매수 시작 — avgPrice = 매수 단가")
    void startFromZero_avgPriceIsFirstBuy() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
        AccountBalance result = balance.applyExecutions(List.of(buy(100, "25.00")));

        assertThat(result.holdings()).isEqualByComparingTo("100");
        assertThat(result.avgPrice()).isEqualByComparingTo("25.0000");
    }

    @Test
    @DisplayName("소수 보유는 wholeShares에서 예외")
    void fractionalHoldings_wholeSharesThrows() {
        AccountBalance balance = new AccountBalance(new BigDecimal("0.5"), BigDecimal.TEN, BigDecimal.ZERO);
        assertThatThrownBy(balance::wholeShares).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("소수 체결을 원장에 반영")
    void fractionalFill_appliedToLedger() {
        AccountBalance result = new AccountBalance(BigDecimal.ZERO, null, BigDecimal.ZERO)
                .applyExecutions(List.of(fillWithAmount(OrderDirection.BUY, "0.00123", "100000")));
        assertThat(result.holdings()).isEqualByComparingTo("0.00123");
        // 체결금액 100000 / 0.00123 = 81300813.00813… → 81300813.01 (scale 2 HALF_UP)
        assertThat(result.avgPrice()).isEqualByComparingTo("81300813.01");
    }

    @Test
    @DisplayName("scale 있는 0 보유는 int 0 잔고와 같고 보유 없음으로 판정")
    void scaledZeroHoldings_equalsIntZero() {
        AccountBalance scaled = new AccountBalance(new BigDecimal("0.00000000"), null, BigDecimal.ZERO);
        assertThat(scaled.wholeShares()).isZero();
        assertThat(scaled.hasHoldings()).isFalse();
        assertThat(scaled).isEqualTo(new AccountBalance(0, null, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("꼬리 0 보유는 정규화")
    void trailingZeroHoldings_normalized() {
        assertThat(new AccountBalance(new BigDecimal("5.00000000"), BigDecimal.ONE, BigDecimal.ZERO).holdings())
                .isEqualTo(new BigDecimal("5"));
    }
}
