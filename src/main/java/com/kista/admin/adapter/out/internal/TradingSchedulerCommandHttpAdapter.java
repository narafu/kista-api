package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingSchedulerCommandPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
class TradingSchedulerCommandHttpAdapter implements TradingSchedulerCommandPort {

    // 트리거는 trading-core에서 백그라운드 가상 스레드를 시작하고 즉시 202를 반환하는 짧은 호출이라
    // (reorder/trade-corrections처럼 브로커 호출을 동기로 감싸지 않음) 공용 짧은 타임아웃 빈을 쓴다
    private final RestClient internalApiRestClient;

    // 순수 트리거(도메인 예외 없음) — 401(INTERNAL_API_TOKEN 미설정)·5xx는 admin의
    // GlobalExceptionHandler catch-all(500 + saveErrorLog)로 떨어지는 것이 적절해 의도적으로
    // onStatus 매핑을 추가하지 않는다 (reorderTimingAvailability와 동일 판단)
    @Override
    public void triggerOpen() {
        internalApiRestClient.post()
                .uri("/api/internal/trading/scheduler/open")
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void triggerClose() {
        internalApiRestClient.post()
                .uri("/api/internal/trading/scheduler/close")
                .retrieve()
                .toBodilessEntity();
    }
}
