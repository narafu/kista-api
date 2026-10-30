package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.trading.domain.model.LedgerCheckPreview;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NextOrdersResponseTest {

    @Test
    void from_mapsLedgerCheckAsNull_whenPreviewLedgerCheckIsNull() {
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, null, null);

        assertThat(NextOrdersResponse.from(preview).ledgerCheck()).isNull();
    }

    @Test
    void from_mapsLedgerCheckFields() {
        LedgerCheckPreview check = new LedgerCheckPreview(
                new BigDecimal("177.61"), new BigDecimal("304.32"), false, true);
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, check, null, null);

        NextOrdersResponse.LedgerCheckSummary summary = NextOrdersResponse.from(preview).ledgerCheck();

        assertThat(summary.remainingLedger()).isEqualByComparingTo("177.61");
        assertThat(summary.requiredBuyUsd()).isEqualByComparingTo("304.32");
        assertThat(summary.sufficient()).isFalse();
        assertThat(summary.trimmed()).isTrue();
    }

    @Test
    void orderItem_mapsTiming() {
        PlannedOrder planned = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("10"), OrderTiming.AT_OPEN);

        assertThat(NextOrdersResponse.OrderItem.from(planned).timing()).isEqualTo(OrderTiming.AT_OPEN);
    }
}
