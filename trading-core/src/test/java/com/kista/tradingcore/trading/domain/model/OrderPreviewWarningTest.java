package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.trading.domain.model.OrderPreviewWarning.Code;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// kista-ui entities/order/model/order-readiness.test.ts 케이스 이식 — timing 없는 ui 케이스는 항상 접수 시점이 도래한 AT_OPEN으로 옮긴다
class OrderPreviewWarningTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 21);
    private static final PlannedOrder BUY = planned(OrderDirection.BUY, OrderTiming.AT_OPEN);
    private static final PlannedOrder SELL = planned(OrderDirection.SELL, OrderTiming.AT_OPEN);
    private static final Order PLACED_SELL = today(OrderDirection.SELL, OrderTiming.AT_OPEN);
    private static final LedgerCheckPreview SHORT = new LedgerCheckPreview(new BigDecimal("50"), new BigDecimal("100"), false, false);

    @Test
    void no_warnings_for_empty_preview() {
        assertThat(OrderPreviewWarning.of(preview(List.of(), List.of(), null, null, null))).isEmpty();
    }

    @Test
    void does_not_mark_buy_unplaced_before_any_order_was_attempted_today() {
        assertThat(codes(preview(List.of(BUY), List.of(), SHORT, null, null))).containsExactly(Code.LEDGER_SHORTFALL);
    }

    @Test
    void marks_buy_unplaced_when_sell_was_placed_but_buy_is_not() {
        assertThat(codes(preview(List.of(BUY, SELL), List.of(PLACED_SELL), SHORT, null, null)))
                .containsExactly(Code.LEDGER_SHORTFALL, Code.BUY_UNPLACED);
    }

    @Test
    void does_not_mark_at_close_buy_unplaced_while_only_at_open_sell_is_pre_placed() {
        assertThat(codes(preview(List.of(planned(OrderDirection.BUY, OrderTiming.AT_CLOSE), planned(OrderDirection.SELL, OrderTiming.AT_OPEN)),
                List.of(today(OrderDirection.SELL, OrderTiming.AT_OPEN)), null, null, null))).isEmpty();
    }

    @Test
    void marks_at_close_buy_unplaced_once_an_at_close_sell_was_placed() {
        assertThat(codes(preview(List.of(planned(OrderDirection.BUY, OrderTiming.AT_CLOSE), planned(OrderDirection.SELL, OrderTiming.AT_CLOSE)),
                List.of(today(OrderDirection.SELL, OrderTiming.AT_CLOSE)), null, null, null))).containsExactly(Code.BUY_UNPLACED);
    }

    @Test
    void marks_at_open_sell_unplaced_when_it_was_not_pre_placed() {
        assertThat(codes(preview(List.of(BUY, SELL), List.of(today(OrderDirection.BUY, OrderTiming.AT_OPEN)), null, null, null)))
                .containsExactly(Code.SELL_UNPLACED);
    }

    @Test
    void ignores_ledger_shortfall_when_no_buy_is_planned() {
        assertThat(codes(preview(List.of(SELL), List.of(), SHORT, null, null))).isEmpty();
    }

    @Test
    void ledger_shortfall_detail_carries_amounts() {
        assertThat(OrderPreviewWarning.of(preview(List.of(BUY), List.of(), SHORT, null, null)).getFirst().detail())
                .isEqualTo("전략 원장 예수금이 부족해 매수가 접수되지 않습니다(원장 $50.00 / 필요 $100.00).");
    }

    @Test
    void account_cash_shortfall_only_when_buy_planned_and_ledger_did_not_reject() {
        AccountCashCheckPreview cashShort = cash("300", "1000", "0", false);

        assertThat(codes(preview(List.of(BUY), List.of(), null, cashShort, null))).containsExactly(Code.ACCOUNT_CASH_SHORTFALL);
        assertThat(codes(preview(List.of(BUY), List.of(), null, cash("300", "1000", "0", true), null))).isEmpty();
        // 계좌 단위 값이라 BUY 계획이 없는 전략에는 경고를 붙이지 않는다
        assertThat(codes(preview(List.of(SELL), List.of(), null, cashShort, null))).isEmpty();
        // 원장 거절이면 BUY가 증권사로 나가지 않는다
        assertThat(codes(preview(List.of(BUY), List.of(), SHORT, cashShort, null))).containsExactly(Code.LEDGER_SHORTFALL);
    }

    @Test
    void account_cash_partial_only_when_shortfall_disappears_once_corrections_are_excluded() {
        AccountCashCheckPreview partial = cash("250", "326.41", "115.6", false);

        List<OrderPreviewWarning> warnings = OrderPreviewWarning.of(preview(List.of(BUY), List.of(), null, partial, null));
        assertThat(warnings).extracting(OrderPreviewWarning::code).containsExactly(Code.ACCOUNT_CASH_PARTIAL);
        // 보정 주문 제외 금액 = 326.41 − 115.60 = 210.81
        assertThat(warnings.getFirst().detail()).isEqualTo("계좌 예수금이 부족해 보정 주문이 거절될 수 있습니다(주문가능 $250.00 / 계좌 전체 접수 필요 $326.41 · 보정 주문 제외 $210.81).");
        // 보정 주문이 없으면 제외 금액 표기도 없고 전체 부족
        List<OrderPreviewWarning> noCorrection = OrderPreviewWarning.of(preview(List.of(BUY), List.of(), null, cash("250", "326.41", "0", false), null));
        assertThat(noCorrection).extracting(OrderPreviewWarning::code).containsExactly(Code.ACCOUNT_CASH_SHORTFALL);
        assertThat(noCorrection.getFirst().detail()).isEqualTo("계좌 예수금이 부족해 매수가 거절될 수 있습니다(주문가능 $250.00 / 계좌 전체 접수 필요 $326.41).");
        // 경계: 0.4 − 0.1 = 0.3 이면 충분(같음)으로 본다
        assertThat(codes(preview(List.of(BUY), List.of(), null, cash("0.3", "0.4", "0.1", false), null))).containsExactly(Code.ACCOUNT_CASH_PARTIAL);
        // 보정 주문을 빼도 부족하면 전체 부족
        assertThat(codes(preview(List.of(BUY), List.of(), null, cash("200", "326.41", "115.6", false), null))).containsExactly(Code.ACCOUNT_CASH_SHORTFALL);
        assertThat(codes(preview(List.of(BUY), List.of(), null, cash("250", "326.41", "115.6", true), null))).isEmpty();
        // 원장 거절이면 계좌 예수금 경고 자체가 없다
        assertThat(codes(preview(List.of(BUY), List.of(), SHORT, partial, null))).containsExactly(Code.LEDGER_SHORTFALL);
    }

    @Test
    void holdings_shortfall_only_when_sell_planned() {
        AccountHoldingsCheckPreview holdingsShort = new AccountHoldingsCheckPreview(new BigDecimal("10"), new BigDecimal("30"), false);

        List<OrderPreviewWarning> warnings = OrderPreviewWarning.of(preview(List.of(SELL), List.of(), null, null, holdingsShort));
        assertThat(warnings).extracting(OrderPreviewWarning::code).containsExactly(Code.HOLDINGS_SHORTFALL);
        assertThat(warnings.getFirst().detail()).isEqualTo("보유 수량이 부족해 매도가 거절될 수 있습니다(판매가능 10 / 종목 전체 필요 30).");
        assertThat(codes(preview(List.of(SELL), List.of(), null, null,
                new AccountHoldingsCheckPreview(new BigDecimal("10"), new BigDecimal("30"), true)))).isEmpty();
        // (계좌, 종목) 단위 값이라 SELL 계획이 없는 전략에는 경고를 붙이지 않는다
        assertThat(codes(preview(List.of(BUY), List.of(), null, null, holdingsShort))).isEmpty();
    }

    private static List<Code> codes(NextOrdersPreview preview) {
        return OrderPreviewWarning.of(preview).stream().map(OrderPreviewWarning::code).toList();
    }

    private static NextOrdersPreview preview(List<PlannedOrder> orders, List<Order> todayOrders, LedgerCheckPreview ledger,
                                             AccountCashCheckPreview cash, AccountHoldingsCheckPreview holdings) {
        return new NextOrdersPreview(DATE, null, orders, null, todayOrders, BigDecimal.ZERO, ledger, cash, holdings, false);
    }

    private static AccountCashCheckPreview cash(String available, String required, String correction, boolean sufficient) {
        return new AccountCashCheckPreview(new BigDecimal(available), new BigDecimal(required), new BigDecimal(correction), sufficient);
    }

    private static PlannedOrder planned(OrderDirection direction, OrderTiming timing) {
        return PlannedOrder.of(DATE, StrategyTicker.MAGX, OrderType.LOC, direction, 1, new BigDecimal("100"), timing);
    }

    private static Order today(OrderDirection direction, OrderTiming timing) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DATE, StrategyTicker.MAGX, OrderType.LOC, timing,
                direction, BigDecimal.ONE, new BigDecimal("120"), OrderStatus.PLACED, "ext-1", null, null);
    }
}
