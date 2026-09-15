package com.kista.admin.adapter.out.internal;

import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategySummary;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingQueryHttpAdapterTest {

    private MockWebServer server;
    private TradingQueryHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new TradingQueryHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void findAllOrders_요청_경로와_쿼리파라미터를_구성한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body("[]").build());
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);

        List<?> result = adapter.findAllOrders(from, to);

        assertThat(result).isEmpty();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/orders");
        assertThat(recorded.getTarget()).contains("from=2026-01-01");
        assertThat(recorded.getTarget()).contains("to=2026-01-31");
    }

    // Order는 15필드 record + orderLeg를 재작성하는 compact 생성자를 갖고, price/filledPrice가
    // 실제 화면에 노출되는 금액 필드다 — 빈 배열([])만으로는 필드별 역직렬화·BigDecimal scale
    // 보존 여부를 전혀 검증하지 못하므로, 모든 필드를 채운 실제 주문 1건으로 왕복 검증한다.
    @Test
    void findAllOrders_전체_필드가_채워진_주문을_필드별로_역직렬화한다() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID strategyCycleId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    [{"id":"%s","accountId":"%s","strategyCycleId":"%s","tradeDate":"2026-07-01",
                      "ticker":"SOXL","orderType":"LIMIT","timing":"AT_CLOSE","direction":"BUY",
                      "orderLeg":"BUY_01","quantity":10,"price":123.45,"status":"PARTIALLY_FILLED",
                      "externalOrderId":"EXT-0001","filledQuantity":5,"filledPrice":123.40}]
                    """.formatted(id, accountId, strategyCycleId))
                .build());

        List<AdminOrderView> result = adapter.findAllOrders(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));

        assertThat(result).hasSize(1);
        AdminOrderView order = result.get(0);
        assertThat(order.id()).isEqualTo(id);
        assertThat(order.accountId()).isEqualTo(accountId);
        assertThat(order.strategyCycleId()).isEqualTo(strategyCycleId);
        assertThat(order.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(order.ticker()).isEqualTo(StrategyTicker.SOXL);
        assertThat(order.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(order.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(order.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(order.orderLeg()).isEqualTo("BUY_01");
        assertThat(order.quantity()).isEqualTo(10);
        assertThat(order.price()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(order.price().scale()).isEqualTo(2); // 금액 scale 보존 확인 — 소수점 드리프트 방지
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.externalOrderId()).isEqualTo("EXT-0001");
        assertThat(order.filledQuantity()).isEqualTo(5);
        assertThat(order.filledPrice()).isEqualByComparingTo(new BigDecimal("123.40"));
        assertThat(order.filledPrice().scale()).isEqualTo(2);
    }

    // Map<UUID, ...> 키 역직렬화는 값(UUID 필드) 역직렬화와 다른 Jackson 코드 경로(KeyDeserializer)를
    // 탄다 — findStrategiesByAccountId(값 경로)만으로는 이 배치 조회 경로가 검증되지 않는다.
    @Test
    void findStrategySummariesByCycleIds_UUID_맵키를_역직렬화한다() {
        UUID cycleId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"%s":{"strategyId":"%s","strategyType":"VR"}}
                    """.formatted(cycleId, strategyId))
                .build());

        Map<UUID, AdminStrategySummary> result = adapter.findStrategySummariesByCycleIds(Set.of(cycleId));

        assertThat(result).containsOnlyKeys(cycleId);
        assertThat(result.get(cycleId).strategyId()).isEqualTo(strategyId);
        assertThat(result.get(cycleId).strategyType()).isEqualTo(StrategyType.VR);
    }

    @Test
    void findStrategiesByAccountId_응답을_역직렬화한다() {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    [{"id":"%s","accountId":"%s","type":"INFINITE","status":"ACTIVE","ticker":"SOXL","cycleSeedType":"NONE"}]
                    """.formatted(strategyId, accountId))
                .build());

        var result = adapter.findStrategiesByAccountId(accountId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(strategyId);
    }

    // trading-core의 TradingInternalQueryController.requireStrategyOwnedByAccount가 소유권
    // 불일치 시 NoSuchElementException(→404)을 던진다 — 어댑터가 이를 되돌리지 못하면 admin의
    // GlobalExceptionHandler가 매핑하지 못하는 HttpClientErrorException.NotFound로 흘러 500이 된다.
    @Test
    void findStrategyOrders_404_응답은_NoSuchElementException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.findStrategyOrders(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findStrategyTradeDates_404_응답은_NoSuchElementException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.findStrategyTradeDates(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findStrategyOrders_정상_응답은_그대로_반환한다() {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body("[]").build());

        var result = adapter.findStrategyOrders(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 1));

        assertThat(result).isEmpty();
    }
}

