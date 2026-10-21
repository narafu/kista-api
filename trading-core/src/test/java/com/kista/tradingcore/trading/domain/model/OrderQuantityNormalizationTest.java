package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderQuantityNormalizationTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID CYCLE = UUID.randomUUID();

    private static Order order(String quantity, String filled) {
        return new Order(ID, ACCOUNT, CYCLE, LocalDate.of(2026, 10, 17),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY,
                new BigDecimal(quantity), new BigDecimal("22.00"), OrderStatus.FILLED, "X1",
                filled == null ? null : new BigDecimal(filled), new BigDecimal("22.00"));
    }

    @Test
    void dbScaleTrailingZerosAreStripped() {
        // numeric(20,8)에서 읽은 값 — equals(scale 민감)와 JSON 모두 정수 주식 수량과 같아야 한다
        assertThat(order("10.00000000", "10.00000000")).isEqualTo(order("10", "10"));
        assertThat(order("100.00000000", null).quantity().toString()).isEqualTo("100"); // 1E+2 금지
        assertThat(order("0.00012300", null).quantity().toString()).isEqualTo("0.000123");
        assertThat(order("0E-8", null).quantity().toString()).isEqualTo("0");
    }

    @Test
    void jsonKeepsIntegerShapeForStock() throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(order("10.00000000", "100.00000000"));
        assertThat(json).contains("\"quantity\":10,").contains("\"filledQuantity\":100,");
    }

    @Test
    void dcaExecutionQuantityIsNormalized() {
        DcaExecution e = DcaExecution.ordered(UUID.randomUUID(), "k", com.kista.shared.kernel.OrderDirection.BUY, LocalDate.of(2026, 10, 17), UUID.randomUUID(),
                new BigDecimal("3.00000000"), new BigDecimal("50.00"));
        assertThat(e.quantity().toString()).isEqualTo("3");
    }
}
