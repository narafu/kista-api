package com.kista.api.admin.adapter.out.internal;

import com.kista.api.admin.application.port.output.TradingCommandPort;
import com.kista.shared.contract.trading.TradeCorrectionRequest;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.shared.platform.internalapi.InternalApiStatusHandlers;
import com.kista.shared.kernel.StrategyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
class TradingCommandHttpAdapter implements TradingCommandPort {

    // trade-corrections는 동기 쓰기 경로라 응답 타임아웃이
    // 더 긴 전용 빈을 쓴다 (InternalApiClientConfig.internalApiWriteRestClient 참고, 필드명으로
    // 빈 매칭됨 — 타입이 RestClient로 동일해 @Qualifier 대신 이름 일치로 주입)
    private final RestClient internalApiWriteRestClient;

    // trading 쪽 ProblemDetailExceptionHandler(전역 공유)가 매핑한 상태코드(404·400)를 admin이 매핑하는 표준 예외로 되돌린다 —
    // 없으면 admin 쪽에서 매핑되지 않는 HttpClientErrorException으로 흘러 500(catch-all)으로 뭉개진다.
    @Override
    public TradeCorrectionResponse correctManualFills(TradeCorrectionRequest command) {
        RestClient.ResponseSpec spec = internalApiWriteRestClient.post()
                .uri("/api/internal/trading/trade-corrections")
                .body(command)
                .retrieve();
        spec = InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "계좌 또는 전략을 찾을 수 없습니다");
        return InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "수동 체결 보정 요청이 유효하지 않습니다")
                .body(TradeCorrectionResponse.class);
    }

    // 계좌·전략 소유권 검증 + 저장은 trading-core 쪽에서 처리 — DB 쓰기 경로라 internalApiWriteRestClient 사용
    @Override
    public void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status) {
        RestClient.ResponseSpec spec = internalApiWriteRestClient.patch()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status")
                        .queryParam("status", status)
                        .build(accountId, strategyId))
                .retrieve();
        spec = InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "계좌 또는 전략을 찾을 수 없습니다");
        InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "전략이 계좌에 속하지 않습니다")
                .toBodilessEntity();
    }
}
