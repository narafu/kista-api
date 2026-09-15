package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminFidaOrderCommand;
import com.kista.admin.domain.model.AdminPrivacyBaseUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// admin이 정의하는 privacy 기준 매매표 조회+쓰기 포트 — PrivacyQueryHttpAdapter가 내부 API로 구현
public interface PrivacyQueryPort {
    List<AdminPrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate);

    // FIDA 오류 대응 수동 등록 — 기존 POST /api/internal/fida-orders(FidaOrderController) 재사용.
    // 응답 body(FidaOrderResponse)엔 created 플래그가 없어(id echo만) HTTP 상태코드(201/200)로 판정한다.
    record CreateBaseResult(AdminPrivacyTradeBaseView view, boolean created) {}
    CreateBaseResult createBase(AdminFidaOrderCommand command);

    AdminPrivacyTradeBaseView updateBase(UUID baseId, AdminPrivacyBaseUpdateCommand command);

    AdminPrivacyTradeBaseView updateOrder(UUID baseId, UUID orderId, AdminPrivacyOrderUpdateCommand command);
}
