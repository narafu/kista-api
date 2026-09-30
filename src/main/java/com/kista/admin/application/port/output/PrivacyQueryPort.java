package com.kista.admin.application.port.output;

import com.kista.contract.privacy.FidaOrderRequest;
import com.kista.contract.privacy.PrivacyBaseUpdateRequest;
import com.kista.contract.privacy.PrivacyOrderAddRequest;
import com.kista.contract.privacy.PrivacyOrderUpdateRequest;
import com.kista.contract.privacy.PrivacyTradeBaseResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// admin이 정의하는 privacy 기준 매매표 조회+쓰기 포트 — PrivacyQueryHttpAdapter가 내부 API로 구현
public interface PrivacyQueryPort {
    List<PrivacyTradeBaseResponse> findBasesFromTradeDate(LocalDate fromReleaseDate);

    // FIDA 오류 대응 수동 등록 — 기존 POST /api/internal/fida-orders(FidaOrderController) 재사용.
    // 응답 body(FidaOrderResponse)엔 created 플래그가 없어(id echo만) HTTP 상태코드(201/200)로 판정한다.
    record CreateBaseResult(PrivacyTradeBaseResponse view, boolean created) {}
    CreateBaseResult createBase(FidaOrderRequest command);

    PrivacyTradeBaseResponse updateBase(UUID baseId, PrivacyBaseUpdateRequest command);

    PrivacyTradeBaseResponse updateOrder(UUID baseId, UUID orderId, PrivacyOrderUpdateRequest command);

    PrivacyTradeBaseResponse addOrder(UUID baseId, PrivacyOrderAddRequest command);

    PrivacyTradeBaseResponse deleteOrder(UUID baseId, UUID orderId);
}
