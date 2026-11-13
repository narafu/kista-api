package com.kista.shared.contract.notify;

import com.kista.shared.kernel.Currency;
import com.kista.shared.platform.PlatformJsonConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TradeEventMessageTest {

    private final ObjectMapper mapper = plainMapper(); // 앱 전역 설정(PlatformJsonConfig) 그대로 적용

    private static ObjectMapper plainMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new PlatformJsonConfig().plainBigDecimalCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    void serializesFractionalQuantityAsPlainNumber() {
        TradeEventMessage e = TradeEventMessage.buy("BTC", new BigDecimal("0.00012345"), 1.0, 1.0, "n", UUID.randomUUID(), null, Currency.USD, Instant.EPOCH);
        assertThat(mapper.writeValueAsString(e)).contains("\"quantity\":0.00012345");
    }

    @Test
    void serializesSmallAndExponentQuantitiesWithoutScientificNotation() {
        assertThat(json(new BigDecimal("0.00000012"))).contains("\"quantity\":0.00000012");
        assertThat(json(new BigDecimal("1E+2"))).contains("\"quantity\":100");
    }

    private String json(BigDecimal q) {
        return mapper.writeValueAsString(TradeEventMessage.buy("BTC", q, 1.0, 1.0, "n", UUID.randomUUID(), null, Currency.USD, Instant.EPOCH));
    }

    @Test
    void buyCarriesCurrency() {
        assertThat(TradeEventMessage.buy("BTC", BigDecimal.ONE, 65000.0, 10.4, "바낸", UUID.randomUUID(), null, Currency.USDT, Instant.EPOCH).currency())
                .isEqualTo(Currency.USDT);
    }

    @Test
    void serializesCurrencyName() {
        assertThat(mapper.writeValueAsString(TradeEventMessage.sell("BTC", BigDecimal.ONE, 1.0, 1.0, "n", null, null, Currency.USDT, Instant.EPOCH)))
                .contains("\"currency\":\"USDT\"");
    }

    @Test
    void legacyJsonWithoutCurrencyDeserializesAsNull() {
        String json = "{\"kind\":\"BUY\",\"ticker\":\"SOXL\",\"quantity\":3,\"price\":1.0,\"amount\":3.0}";
        assertThat(mapper.readValue(json, TradeEventMessage.class).currency()).isNull();
    }

    @Test
    void deserializesIntegerQuantity() {
        String json = "{\"kind\":\"BUY\",\"ticker\":\"SOXL\",\"quantity\":3,\"price\":1.0,\"amount\":3.0}";
        TradeEventMessage e = mapper.readValue(json, TradeEventMessage.class);
        assertThat(e.quantity()).isEqualByComparingTo("3");
    }
}
