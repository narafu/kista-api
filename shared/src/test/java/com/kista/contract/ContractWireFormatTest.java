package com.kista.contract;

import com.kista.contract.privacy.FidaOrderRequest;
import com.kista.contract.privacy.PrivacyOrderAddRequest;
import com.kista.contract.stats.InvestmentPointsResponse;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// contract wire 스키마 고정 — 외부 FIDA 프로젝트가 호출하는 body와 프로세스 간 JSON 필드명이 바뀌지 않는지 검증
@DisplayName("contract wire 포맷")
class ContractWireFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("FidaOrderRequest — FIDA가 보내는 tradeDate 별칭 JSON을 releaseDate로 역직렬화한다")
    void fida_request_accepts_trade_date_alias() {
        String json = """
                {"tradeDate":"2026-09-30","ticker":"SOXL","currentCycleStart":100.5,"currentCycleRealizedPnl":-3.2,
                 "avgPrice":null,"holdings":0,
                 "orders":[{"direction":"BUY","orderType":"LOC","quantity":3,"price":24.5},
                           {"direction":"SELL","orderType":"LIMIT","quantity":null,"price":26.0}]}
                """;

        FidaOrderRequest request = objectMapper.readValue(json, FidaOrderRequest.class);

        assertThat(request.releaseDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(request.ticker()).isEqualTo(StrategyTicker.SOXL);
        assertThat(request.orders()).hasSize(2);
        assertThat(request.orders().get(1).quantity()).isNull();
        assertThat(request.isBuyQuantityValid()).isTrue();
    }

    @Test
    @DisplayName("FidaOrderRequest — 검증 헬퍼(isBuyQuantityValid)는 JSON으로 직렬화되지 않는다")
    void fida_request_does_not_serialize_validation_helper() {
        FidaOrderRequest request = new FidaOrderRequest(LocalDate.of(2026, 9, 30), StrategyTicker.SOXL,
                BigDecimal.TEN, BigDecimal.ONE, null, 0, java.util.List.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"releaseDate\"").doesNotContain("buyQuantityValid");
    }

    @Test
    @DisplayName("BUY 주문의 quantity가 null이면 검증 헬퍼가 false를 돌려준다")
    void buy_without_quantity_is_invalid() {
        FidaOrderRequest fida = new FidaOrderRequest(LocalDate.of(2026, 9, 30), StrategyTicker.SOXL,
                BigDecimal.TEN, BigDecimal.ONE, null, 0,
                java.util.List.of(new FidaOrderRequest.PlannedOrder(OrderDirection.BUY, OrderType.LOC, null, BigDecimal.ONE)));
        PrivacyOrderAddRequest add = new PrivacyOrderAddRequest(OrderDirection.BUY, OrderType.LOC, BigDecimal.ONE, null);

        assertThat(fida.isBuyQuantityValid()).isFalse();
        assertThat(add.isBuyQuantityValid()).isFalse();
        assertThat(new PrivacyOrderAddRequest(OrderDirection.SELL, OrderType.LOC, BigDecimal.ONE, null).isBuyQuantityValid()).isTrue();
    }

    @Test
    @DisplayName("InvestmentPointsResponse — 중첩 DTO를 포함한 JSON 왕복이 동일하다")
    void investment_points_round_trip() {
        InvestmentPointsResponse response = new InvestmentPointsResponse(
                java.util.List.of(new InvestmentPointsResponse.InvestmentPointDto(
                        LocalDate.of(2026, 9, 1), new BigDecimal("101.5"), new BigDecimal("0.015"))),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);

        InvestmentPointsResponse back = objectMapper.readValue(objectMapper.writeValueAsString(response), InvestmentPointsResponse.class);

        assertThat(back).isEqualTo(response);
    }
}
