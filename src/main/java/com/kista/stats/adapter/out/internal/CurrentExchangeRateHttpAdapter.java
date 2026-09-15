package com.kista.stats.adapter.out.internal;

import com.kista.stats.application.port.output.CurrentExchangeRatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
class CurrentExchangeRateHttpAdapter implements CurrentExchangeRatePort {

    private final RestClient internalApiRestClient;

    @Override
    public BigDecimal getMidRate() {
        // 네트워크 오류·역직렬화 실패 등은 StatsService의 "환율 조회 실패 시 null" 계약을 따라
        // 여기서 흡수한다 — 실패해도 벤치마크 비교 본체는 정상 응답돼야 하기 때문. 실패를 삼키기
        // 전에 여기서 warn을 남겨야 한다 — 이 이후로는 아무도 예외를 보지 못한다(StatsService의
        // 방어적 try/catch는 이 어댑터가 실패를 던지지 않는 한 도달하지 않는다).
        try {
            return internalApiRestClient.get()
                    .uri("/api/internal/trading/stats/exchange-rate")
                    .retrieve()
                    .body(BigDecimal.class);
        } catch (RuntimeException e) {
            log.warn("현재 USD/KRW 환율 내부 API 조회 실패", e);
            return null;
        }
    }
}
