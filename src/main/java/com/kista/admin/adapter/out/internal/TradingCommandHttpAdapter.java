package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
import com.kista.admin.domain.model.AdminBrokerCredentialException;
import com.kista.admin.domain.model.AdminBrokerRateLimitException;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.platform.internalapi.InternalApiErrorDetails;
import com.kista.sharedkernel.StrategyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.NoSuchElementException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class TradingCommandHttpAdapter implements TradingCommandPort {

    // 순수 조회(reorderTimingAvailability)용 — 공용 짧은 타임아웃 빈
    private final RestClient internalApiRestClient;

    // reorder/trade-corrections는 브로커 취소+접수를 동기로 감싸는 쓰기 경로라 응답 타임아웃이
    // 더 긴 전용 빈을 쓴다 (InternalApiClientConfig.internalApiWriteRestClient 참고, 필드명으로
    // 빈 매칭됨 — 타입이 RestClient로 동일해 @Qualifier 대신 이름 일치로 주입)
    private final RestClient internalApiWriteRestClient;

    // trading 쪽 GlobalExceptionHandler(전역 공유)가 매핑한 상태코드를 admin 소유 표지 예외 타입으로 되돌린다 —
    // 없으면 IllegalArgumentException/IllegalStateException/NoSuchElementException/AdminBrokerCredentialException/
    // AdminBrokerRateLimitException이 admin 쪽에서 매핑되지 않는 HttpClientErrorException으로 흘러 500(catch-all)으로 뭉개진다.
    // 추적한 실패 모드: 계좌·전략 미존재(404), 소유권 불일치·주문 미존재·시점/휴장일 검증 실패·SELL 초과(400,
    // IllegalArgumentException·IllegalStateException 모두 GlobalExceptionHandler에서 400으로 동일 매핑되므로
    // 되돌릴 때도 하나의 타입으로 합쳐도 관찰 가능한 응답이 동일), 증권사 자격증명 오류(422), 증권사 rate limit(429).
    // 각 케이스는 trading 쪽 GlobalExceptionHandler가 ProblemDetail.detail에 실은 원 메시지를 그대로
    // 옮겨 담아 admin 운영자가 9가지 거절 사유를 구분할 수 있게 한다(고정 문구로 뭉개지 않음).
    @Override
    public AdminReorderResult reorder(AdminReorderCommand command) {
        return internalApiWriteRestClient.post()
                .uri("/api/internal/trading/reorder")
                .body(command)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "계좌 또는 전략을 찾을 수 없습니다"));
                })
                .onStatus(status -> status.value() == 400, (request, response) -> {
                    throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(response, "재주문 요청이 유효하지 않습니다"));
                })
                .onStatus(status -> status.value() == 422, (request, response) -> {
                    throw new AdminBrokerCredentialException();
                })
                .onStatus(status -> status.value() == 429, (request, response) -> {
                    throw new AdminBrokerRateLimitException();
                })
                .body(AdminReorderResult.class);
    }

    @Override
    public AdminTradeCorrectionResult correctManualFills(AdminManualTradeCorrectionCommand command) {
        return internalApiWriteRestClient.post()
                .uri("/api/internal/trading/trade-corrections")
                .body(command)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "계좌 또는 전략을 찾을 수 없습니다"));
                })
                .onStatus(status -> status.value() == 400, (request, response) -> {
                    throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(response, "수동 체결 보정 요청이 유효하지 않습니다"));
                })
                .body(AdminTradeCorrectionResult.class);
    }

    // 순수 조회(DstInfo.calculate()의 로컬 계산)라 도메인 예외가 없다 — 401(INTERNAL_API_TOKEN 미설정)·
    // 5xx(trading 쪽 장애)뿐이며 둘 다 인프라 오류이므로 admin의 GlobalExceptionHandler catch-all(500 +
    // saveErrorLog)로 떨어지는 것이 적절한 동작이다. 의도적으로 onStatus 매핑을 추가하지 않는다.
    @Override
    public AdminReorderTimingAvailability reorderTimingAvailability() {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/reorder-timing-availability")
                .retrieve()
                .body(AdminReorderTimingAvailability.class);
    }

    // 계좌·전략 소유권 검증 + 저장은 trading-core 쪽에서 처리 — DB 쓰기 경로라 internalApiWriteRestClient 사용
    @Override
    public void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status) {
        internalApiWriteRestClient.patch()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status")
                        .queryParam("status", status)
                        .build(accountId, strategyId))
                .retrieve()
                .onStatus(status2 -> status2.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "계좌 또는 전략을 찾을 수 없습니다"));
                })
                .onStatus(status2 -> status2.value() == 400, (request, response) -> {
                    throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(response, "전략이 계좌에 속하지 않습니다"));
                })
                .toBodilessEntity();
    }
}
