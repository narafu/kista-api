package com.kista.api.admin.adapter.out.internal;

import com.kista.api.admin.application.port.output.TradingQueryPort;
import com.kista.shared.contract.trading.OrderResponse;
import com.kista.shared.contract.trading.StrategySummaryResponse;
import com.kista.shared.contract.trading.StrategyResponse;
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
}
