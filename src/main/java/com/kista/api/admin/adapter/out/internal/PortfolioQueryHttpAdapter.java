package com.kista.api.admin.adapter.out.internal;

import com.kista.shared.contract.stats.PortfolioCurrentResponse;
import com.kista.shared.contract.stats.PortfolioOrderResponse;
import com.kista.api.admin.application.port.output.PortfolioQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class PortfolioQueryHttpAdapter implements PortfolioQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<PortfolioCurrentResponse> getCurrent(UUID userId) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/stats/portfolio/current").queryParam("userId", userId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<PortfolioCurrentResponse>>() {});
    }

    @Override
    public List<PortfolioOrderResponse> getHistory(UUID userId, LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/stats/portfolio/history")
                        .queryParam("userId", userId).queryParam("from", from).queryParam("to", to)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<PortfolioOrderResponse>>() {});
    }
}
