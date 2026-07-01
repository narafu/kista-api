package com.kista.domain.model.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// TradeEvent.Kind enum이 Jackson name()으로 직렬화되는지 검증 — SSE 페이로드 계약 (kista-ui 타입: 'BUY'|'SELL'|'INFO'|'FAIL')
@DisplayName("TradeEvent 직렬화 — SSE 페이로드 계약")
class TradeEventSerializationTest {

    // Spring Boot ObjectMapper와 동일하게 JavaTimeModule 등록 (Instant 직렬화)
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("BUY 이벤트 — kind 필드가 문자열 'BUY'로 직렬화")
    void buy_kind_serializes_as_string() throws Exception {
        TradeEvent event = TradeEvent.buy("SOXL", 5, 22.50, 112.50, "테스트계좌");
        String json = objectMapper.writeValueAsString(event);
        assertThat(json).contains("\"kind\":\"BUY\"");
    }

    @Test
    @DisplayName("SELL 이벤트 — kind 필드가 문자열 'SELL'로 직렬화")
    void sell_kind_serializes_as_string() throws Exception {
        TradeEvent event = TradeEvent.sell("SOXL", 3, 23.00, 69.00, "테스트계좌");
        String json = objectMapper.writeValueAsString(event);
        assertThat(json).contains("\"kind\":\"SELL\"");
    }

    @Test
    @DisplayName("FAIL 이벤트 — kind 필드가 문자열 'FAIL'로 직렬화")
    void fail_kind_serializes_as_string() throws Exception {
        TradeEvent event = TradeEvent.fail("SOXL", "주문 접수 실패", "테스트계좌");
        String json = objectMapper.writeValueAsString(event);
        assertThat(json).contains("\"kind\":\"FAIL\"");
    }
}
