package com.kista.shared.contract.notify;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TradeEventMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesFractionalQuantityAsPlainNumber() {
        TradeEventMessage e = TradeEventMessage.buy("BTC", new BigDecimal("0.00012345"), 1.0, 1.0, "n", UUID.randomUUID(), null);
        assertThat(mapper.writeValueAsString(e)).contains("\"quantity\":0.00012345");
    }

    @Test
    void serializesSmallAndExponentQuantitiesWithoutScientificNotation() {
        assertThat(json(new BigDecimal("0.00000012"))).contains("\"quantity\":0.00000012");
        assertThat(json(new BigDecimal("1E+2"))).contains("\"quantity\":100");
    }

    private String json(BigDecimal q) {
        return mapper.writeValueAsString(TradeEventMessage.buy("BTC", q, 1.0, 1.0, "n", UUID.randomUUID(), null));
    }

    @Test
    void deserializesIntegerQuantity() {
        String json = "{\"kind\":\"BUY\",\"ticker\":\"SOXL\",\"quantity\":3,\"price\":1.0,\"amount\":3.0}";
        TradeEventMessage e = mapper.readValue(json, TradeEventMessage.class);
        assertThat(e.quantity()).isEqualByComparingTo("3");
    }
}
