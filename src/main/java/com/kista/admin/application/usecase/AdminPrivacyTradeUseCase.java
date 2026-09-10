package com.kista.admin.application.usecase;

import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyBaseUpdateCommand;
import com.kista.privacy.domain.model.PrivacyOrderUpdateCommand;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;

import java.util.UUID;

// 관리자 PRIVACY 기준 매매표 수동 보정 — 등록(FIDA 오류 대응)·마스터 필드 수정·개별 주문 가격·수량 수정
public interface AdminPrivacyTradeUseCase {
    // FIDA 수신 경로(executeFidaOrder) 재사용 — 동일 (releaseDate, ticker) 존재+내용 동일 시 created=false(멱등)
    record CreateResult(PrivacyTradeBaseView view, boolean created) {}

    CreateResult createBase(UUID adminId, FidaOrderCommand command);

    PrivacyTradeBaseView updateBase(UUID adminId, UUID baseId, PrivacyBaseUpdateCommand command);

    PrivacyTradeBaseView updateOrder(UUID adminId, UUID baseId, UUID orderId, PrivacyOrderUpdateCommand command);
}
