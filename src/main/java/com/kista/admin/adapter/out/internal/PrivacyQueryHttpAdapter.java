package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.PrivacyQueryPort;
import com.kista.contract.privacy.FidaOrderRequest;
import com.kista.contract.privacy.FidaOrderResponse;
import com.kista.contract.privacy.PrivacyBaseUpdateRequest;
import com.kista.contract.privacy.PrivacyOrderAddRequest;
import com.kista.contract.privacy.PrivacyOrderUpdateRequest;
import com.kista.contract.privacy.PrivacyTradeBaseResponse;
import com.kista.admin.domain.model.AdminPrivacyTradeConflictException;
import com.kista.platform.internalapi.InternalApiErrorDetails;
import com.kista.platform.internalapi.InternalApiStatusHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
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
    public List<PrivacyTradeBaseResponse> findBasesFromTradeDate(LocalDate fromReleaseDate) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/privacy/trade-bases").queryParam("fromReleaseDate", fromReleaseDate).build())
                .retrieve().body(new ParameterizedTypeReference<List<PrivacyTradeBaseResponse>>() {});
    }

    @Override
    public CreateBaseResult createBase(FidaOrderRequest command) {
        // 기존 FidaOrderController(POST /api/internal/fida-orders)를 그대로 호출 — 응답 body(FidaOrderResponse)엔
        // 주문 명세 id가 없어(echo 전용) 상태코드로 created만 판정하고, 전체 view는 id로 재조회한다.
        RestClient.ResponseSpec spec = internalApiWriteRestClient.post()
                .uri("/api/internal/fida-orders")
                .body(command)
                .retrieve();
        spec = InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "PRIVACY 기준 매매표 등록 요청이 유효하지 않습니다");
        // FidaOrderController가 같은 (releaseDate, ticker)에 내용이 다른 데이터가 이미 있으면
        // PrivacyTradeConflictException(→409)을 던진다 — 여기서 되돌리지 않으면 admin의
        // GlobalExceptionHandler가 매핑하지 못하는 HttpClientErrorException.Conflict로 흘러
        // 500(catch-all)으로 뭉개진다. 409는 admin 전용 표지 예외라 공용 팩토리 대상이 아니다.
        ResponseEntity<FidaOrderResponse> response = spec
                .onStatus(status -> status.value() == 409, (request, resp) -> {
                    throw new AdminPrivacyTradeConflictException(
                            InternalApiErrorDetails.detailOrDefault(resp, "같은 날짜/종목에 내용이 다른 PRIVACY 기준 매매표가 이미 존재합니다"));
                })
                .toEntity(FidaOrderResponse.class);
        boolean created = response.getStatusCode().value() == 201;
        UUID id = response.getBody().id();
        PrivacyTradeBaseResponse view = internalApiRestClient.get()
                .uri("/api/internal/privacy/trade-bases/{id}", id)
                .retrieve()
                .body(PrivacyTradeBaseResponse.class);
        return new CreateBaseResult(view, created);
    }

    @Override
    public PrivacyTradeBaseResponse updateBase(UUID baseId, PrivacyBaseUpdateRequest command) {
        // trading 쪽 PrivacyTradePersistenceAdapter.updateBase가 baseId 미존재 시 NoSuchElementException(→404)을 던진다 —
        // 여기서 되돌리지 않으면 admin의 GlobalExceptionHandler가 매핑하지 못하는 HttpClientErrorException.NotFound로
        // 흘러 500(catch-all)으로 뭉개진다.
        RestClient.ResponseSpec spec = internalApiWriteRestClient.patch()
                .uri("/api/internal/privacy/trade-bases/{baseId}", baseId)
                .body(command)
                .retrieve();
        spec = InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "PRIVACY 기준 매매표를 찾을 수 없습니다: " + baseId);
        return InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "PRIVACY 기준 매매표 수정 요청이 유효하지 않습니다")
                .body(PrivacyTradeBaseResponse.class);
    }

    @Override
    public PrivacyTradeBaseResponse updateOrder(UUID baseId, UUID orderId, PrivacyOrderUpdateRequest command) {
        RestClient.ResponseSpec spec = internalApiWriteRestClient.patch()
                .uri("/api/internal/privacy/trade-bases/{baseId}/orders/{orderId}", baseId, orderId)
                .body(command)
                .retrieve();
        spec = InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "PRIVACY 기준 매매표 또는 주문 명세를 찾을 수 없습니다: " + orderId);
        return InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "PRIVACY 주문 명세 수정 요청이 유효하지 않습니다")
                .body(PrivacyTradeBaseResponse.class);
    }

    @Override
    public PrivacyTradeBaseResponse addOrder(UUID baseId, PrivacyOrderAddRequest command) {
        RestClient.ResponseSpec spec = internalApiWriteRestClient.post()
                .uri("/api/internal/privacy/trade-bases/{baseId}/orders", baseId)
                .body(command)
                .retrieve();
        spec = InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "PRIVACY 기준 매매표를 찾을 수 없습니다: " + baseId);
        return InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "PRIVACY 주문 명세 추가 요청이 유효하지 않습니다")
                .body(PrivacyTradeBaseResponse.class);
    }

    @Override
    public PrivacyTradeBaseResponse deleteOrder(UUID baseId, UUID orderId) {
        RestClient.ResponseSpec spec = internalApiWriteRestClient.delete()
                .uri("/api/internal/privacy/trade-bases/{baseId}/orders/{orderId}", baseId, orderId)
                .retrieve();
        spec = InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "PRIVACY 기준 매매표 또는 주문 명세를 찾을 수 없습니다: " + orderId);
        return InternalApiStatusHandlers.badRequestAsIllegalArgument(spec, "최소 1건의 주문 명세는 남아있어야 합니다")
                .body(PrivacyTradeBaseResponse.class);
    }
}
