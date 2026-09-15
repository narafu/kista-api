package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.PrivacyQueryPort;
import com.kista.admin.domain.model.AdminFidaOrderCommand;
import com.kista.admin.domain.model.AdminPrivacyBaseUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;
import com.kista.admin.domain.model.AdminPrivacyTradeConflictException;
import com.kista.platform.internalapi.InternalApiErrorDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class PrivacyQueryHttpAdapter implements PrivacyQueryPort {

    // 순수 조회용 — 공용 짧은 타임아웃 빈
    private final RestClient internalApiRestClient;

    // createBase/updateBase/updateOrder는 DB 쓰기를 동반하는 경로라 응답 타임아웃이 더 긴 전용 빈을 쓴다
    // (TradingCommandHttpAdapter와 동일 관례 — InternalApiClientConfig.internalApiWriteRestClient, 필드명으로 빈 매칭)
    private final RestClient internalApiWriteRestClient;

    @Override
    public List<AdminPrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/privacy/trade-bases").queryParam("fromReleaseDate", fromReleaseDate).build())
                .retrieve().body(new ParameterizedTypeReference<List<AdminPrivacyTradeBaseView>>() {});
    }

    @Override
    public CreateBaseResult createBase(AdminFidaOrderCommand command) {
        // 기존 FidaOrderController(POST /api/internal/fida-orders)를 그대로 호출 — 응답 body(FidaOrderResponse)엔
        // 주문 명세 id가 없어(echo 전용) 상태코드로 created만 판정하고, 전체 view는 id로 재조회한다.
        ResponseEntity<FidaCreateAck> response = internalApiWriteRestClient.post()
                .uri("/api/internal/fida-orders")
                .body(command)
                .retrieve()
                .onStatus(status -> status.value() == 400, (request, resp) -> {
                    throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(resp, "PRIVACY 기준 매매표 등록 요청이 유효하지 않습니다"));
                })
                // FidaOrderController가 같은 (releaseDate, ticker)에 내용이 다른 데이터가 이미 있으면
                // PrivacyTradeConflictException(→409)을 던진다 — 여기서 되돌리지 않으면 admin의
                // GlobalExceptionHandler가 매핑하지 못하는 HttpClientErrorException.Conflict로 흘러
                // 500(catch-all)으로 뭉개진다.
                .onStatus(status -> status.value() == 409, (request, resp) -> {
                    throw new AdminPrivacyTradeConflictException(
                            InternalApiErrorDetails.detailOrDefault(resp, "같은 날짜/종목에 내용이 다른 PRIVACY 기준 매매표가 이미 존재합니다"));
                })
                .toEntity(FidaCreateAck.class);
        boolean created = response.getStatusCode().value() == 201;
        UUID id = response.getBody().id();
        AdminPrivacyTradeBaseView view = internalApiRestClient.get()
                .uri("/api/internal/privacy/trade-bases/{id}", id)
                .retrieve()
                .body(AdminPrivacyTradeBaseView.class);
        return new CreateBaseResult(view, created);
    }

    @Override
    public AdminPrivacyTradeBaseView updateBase(UUID baseId, AdminPrivacyBaseUpdateCommand command) {
        return internalApiWriteRestClient.patch()
                .uri("/api/internal/privacy/trade-bases/{baseId}", baseId)
                .body(command)
                .retrieve()
                // trading 쪽 PrivacyTradePersistenceAdapter.updateBase가 baseId 미존재 시 NoSuchElementException(→404)을 던진다 —
                // 여기서 되돌리지 않으면 admin의 GlobalExceptionHandler가 매핑하지 못하는 HttpClientErrorException.NotFound로
                // 흘러 500(catch-all)으로 뭉개진다.
                .onStatus(status -> status.value() == 404, (request, resp) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(resp, "PRIVACY 기준 매매표를 찾을 수 없습니다: " + baseId));
                })
                .onStatus(status -> status.value() == 400, (request, resp) -> {
                    throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(resp, "PRIVACY 기준 매매표 수정 요청이 유효하지 않습니다"));
                })
                .body(AdminPrivacyTradeBaseView.class);
    }

    @Override
    public AdminPrivacyTradeBaseView updateOrder(UUID baseId, UUID orderId, AdminPrivacyOrderUpdateCommand command) {
        return internalApiWriteRestClient.patch()
                .uri("/api/internal/privacy/trade-bases/{baseId}/orders/{orderId}", baseId, orderId)
                .body(command)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, resp) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(resp, "PRIVACY 기준 매매표 또는 주문 명세를 찾을 수 없습니다: " + orderId));
                })
                .onStatus(status -> status.value() == 400, (request, resp) -> {
                    throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(resp, "PRIVACY 주문 명세 수정 요청이 유효하지 않습니다"));
                })
                .body(AdminPrivacyTradeBaseView.class);
    }

    // FidaOrderResponse의 id 필드만 필요 — 나머지 필드는 Jackson이 무시(FAIL_ON_UNKNOWN_PROPERTIES=false)
    private record FidaCreateAck(UUID id) {}
}
