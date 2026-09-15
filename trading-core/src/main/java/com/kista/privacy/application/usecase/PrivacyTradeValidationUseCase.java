package com.kista.privacy.application.usecase;

import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.privacy.domain.model.PrivacyTradeValidationReport;

// PRIVACY 기준 매매표 방어 규칙 검증 — 스케쥴러(adapter.in)와 서비스 레이어 공유 인터페이스
public interface PrivacyTradeValidationUseCase {
    PrivacyTradeValidationReport inspect(FidaOrderCommand command);
    PrivacyTradeValidationReport inspect(PrivacyTradeBase base);
}
