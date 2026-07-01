package com.kista.domain.port.in;

import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;

// PRIVACY 기준 매매표 방어 규칙 검증 — 스케쥴러(adapter.in)와 서비스 레이어 공유 인터페이스
public interface PrivacyTradeValidationUseCase {
    PrivacyTradeValidationReport inspect(FidaOrderCommand command);
    PrivacyTradeValidationReport inspect(PrivacyTradeBase base);
}
