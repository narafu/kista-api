package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingQueryPort;
import com.kista.contract.trading.OrderResponse;
import com.kista.contract.trading.StrategySummaryResponse;
import com.kista.contract.trading.StrategyResponse;
import com.kista.platform.internalapi.InternalApiStatusHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class TradingQueryHttpAdapter implements TradingQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<OrderResponse> findAllOrders(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<OrderResponse>>() {});
    }

    @Override
    public List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders/distinct-account-ids").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
    }

    @Override
    public List<StrategyResponse> findStrategiesByAccountId(UUID accountId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies", accountId)
                .retrieve().body(new ParameterizedTypeReference<List<StrategyResponse>>() {});
    }

    @Override
    public Map<UUID, List<StrategyResponse>> findStrategiesByAccountIds(Set<UUID> accountIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategies/by-account-ids")
                .body(accountIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, List<StrategyResponse>>>() {});
    }

    @Override
    public Map<UUID, StrategySummaryResponse> findStrategySummariesByCycleIds(Set<UUID> cycleIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategy-summaries")
                .body(cycleIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, StrategySummaryResponse>>() {});
    }

    @Override
    public List<OrderResponse> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate) {
        // trading 쪽 컨트롤러가 소유권 불일치 시 NoSuchElementException(→404)을 던진다 —
        // 여기서 되돌리지 않으면 admin의 GlobalExceptionHandler가 매핑하지 못하는
        // HttpClientErrorException.NotFound로 흘러 500(catch-all)으로 뭉개진다.
        RestClient.ResponseSpec spec = internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders")
                        .queryParam("tradeDate", tradeDate).build(accountId, strategyId))
                .retrieve();
        return InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "전략이 해당 계좌에 속하지 않습니다")
                .body(new ParameterizedTypeReference<List<OrderResponse>>() {});
    }

    @Override
    public List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId) {
        RestClient.ResponseSpec spec = internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates", accountId, strategyId)
                .retrieve();
        return InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "전략이 해당 계좌에 속하지 않습니다")
                .body(new ParameterizedTypeReference<List<LocalDate>>() {});
    }
}
