package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingQueryPort;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategySummary;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.platform.internalapi.InternalApiErrorDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class TradingQueryHttpAdapter implements TradingQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<AdminOrderView> findAllOrders(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<AdminOrderView>>() {});
    }

    @Override
    public List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders/distinct-account-ids").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
    }

    @Override
    public List<AdminStrategyView> findStrategiesByAccountId(UUID accountId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies", accountId)
                .retrieve().body(new ParameterizedTypeReference<List<AdminStrategyView>>() {});
    }

    @Override
    public Map<UUID, List<AdminStrategyView>> findStrategiesByAccountIds(Set<UUID> accountIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategies/by-account-ids")
                .body(accountIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, List<AdminStrategyView>>>() {});
    }

    @Override
    public Map<UUID, AdminStrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategy-summaries")
                .body(cycleIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, AdminStrategySummary>>() {});
    }

    @Override
    public List<AdminOrderView> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders")
                        .queryParam("tradeDate", tradeDate).build(accountId, strategyId))
                .retrieve()
                // trading 쪽 컨트롤러가 소유권 불일치 시 NoSuchElementException(→404)을 던진다 —
                // 여기서 되돌리지 않으면 admin의 GlobalExceptionHandler가 매핑하지 못하는
                // HttpClientErrorException.NotFound로 흘러 500(catch-all)으로 뭉개진다.
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "전략이 해당 계좌에 속하지 않습니다"));
                })
                .body(new ParameterizedTypeReference<List<AdminOrderView>>() {});
    }

    @Override
    public List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates", accountId, strategyId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "전략이 해당 계좌에 속하지 않습니다"));
                })
                .body(new ParameterizedTypeReference<List<LocalDate>>() {});
    }
}
