package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.AccountQueryPort;
import com.kista.admin.domain.model.AdminAccountView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class AccountQueryHttpAdapter implements AccountQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<AdminAccountView> findAll(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> {
                    b.path("/api/internal/accounts");
                    if (from != null) b.queryParam("from", from);
                    if (to != null) b.queryParam("to", to);
                    return b.build();
                })
                .retrieve().body(new ParameterizedTypeReference<List<AdminAccountView>>() {});
    }

    @Override
    public Optional<AdminAccountView> findById(UUID accountId) {
        try {
            return Optional.ofNullable(internalApiRestClient.get()
                    .uri("/api/internal/accounts/{id}", accountId)
                    .retrieve().body(AdminAccountView.class));
        } catch (HttpClientErrorException.NotFound e) {
            // trading-core 쪽 findByIdOrThrow가 NoSuchElementException -> 404로 매핑되는 경우 —
            // 없음을 나타내는 Optional.empty()로 되돌린다
            return Optional.empty();
        }
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
