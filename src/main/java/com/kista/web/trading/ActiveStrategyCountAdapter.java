package com.kista.web.trading;

import com.kista.user.application.port.output.ActiveStrategyCountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

// ActiveStrategyCountPort 구현 — user↔strategy-config 순환 해소 산물.
// trading-core 모듈 분리 후 계산 로직(account/trading 의존)을 root에 컴파일 의존시키면 :api→:trading-core
// 단방향 원칙이 깨진다 — trading-core 신규 내부 API(GET /api/internal/trading/active-strategy-count)로
// 계산을 이관하고 이 어댑터는 HTTP 호출만 담당한다. TradingCommandHttpAdapter와 동일한
// internalApiRestClient 빈(필드명 일치로 주입) 재사용.
@Component
@RequiredArgsConstructor
class ActiveStrategyCountAdapter implements ActiveStrategyCountPort {

    private final RestClient internalApiRestClient;

    @Override
    public long countActiveByUserId(UUID userId) {
        Long count = internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/trading/active-strategy-count")
                        .queryParam("userId", userId)
                        .build())
                .retrieve()
                .body(Long.class);
        return count != null ? count : 0L;
    }
}
