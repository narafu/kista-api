package com.kista.api.admin.adapter.out.internal;

import com.kista.api.admin.application.port.output.AccountQueryPort;
import com.kista.shared.contract.account.AccountSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
class AccountQueryHttpAdapter implements AccountQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<AccountSummaryResponse> findAll(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> {
                    b.path("/api/internal/accounts");
                    if (from != null) b.queryParam("from", from);
                    if (to != null) b.queryParam("to", to);
                    return b.build();
                })
                .retrieve().body(new ParameterizedTypeReference<List<AccountSummaryResponse>>() {});
    }

    @Override
    public long countAll() {
        Long count = internalApiRestClient.get()
                .uri("/api/internal/accounts/count")
                .retrieve()
                .body(Long.class);
        return count != null ? count : 0L;
    }
}
