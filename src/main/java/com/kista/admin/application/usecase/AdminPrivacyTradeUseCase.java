package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.AdminFidaOrderCommand;
import com.kista.admin.domain.model.AdminPrivacyBaseUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderAddCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;

import java.util.UUID;

// 관리자 PRIVACY 기준 매매표 수동 보정 — 등록(FIDA 오류 대응)·마스터 필드 수정·개별 주문 가격·수량 수정
public interface AdminPrivacyTradeUseCase {
    // FIDA 수신 경로(executeFidaOrder) 재사용 — 동일 (releaseDate, ticker) 존재+내용 동일 시 created=false(멱등)
    record CreateResult(AdminPrivacyTradeBaseView view, boolean created) {}

    CreateResult createBase(UUID adminId, AdminFidaOrderCommand command);

    AdminPrivacyTradeBaseView updateBase(UUID adminId, UUID baseId, AdminPrivacyBaseUpdateCommand command);

    AdminPrivacyTradeBaseView updateOrder(UUID adminId, UUID baseId, UUID orderId, AdminPrivacyOrderUpdateCommand command);

    AdminPrivacyTradeBaseView addOrder(UUID adminId, UUID baseId, AdminPrivacyOrderAddCommand command);

    AdminPrivacyTradeBaseView deleteOrder(UUID adminId, UUID baseId, UUID orderId);
}
