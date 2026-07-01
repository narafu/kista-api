package com.kista.domain.port.in;

import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;

// PRIVACY 전략 FIDA 주문 처리 인터페이스
public interface PrivacyUseCase {
    // FIDA 주문 수신 처리 — 멱등 (같은 날짜+종목 동일 내용이면 200, 다른 내용이면 PrivacyTradeConflictException→409)
    PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command);
}
