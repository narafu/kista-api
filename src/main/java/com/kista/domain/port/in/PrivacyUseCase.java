package com.kista.domain.port.in;

import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyCurrentBase;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;

// PRIVACY 전략 기준매매표 조회 + FIDA 주문 처리 통합 인터페이스
public interface PrivacyUseCase {
    // trade_date >= 오늘인 행 중 가장 미래의 기준가 반환. 없으면 NoSuchElementException
    PrivacyCurrentBase getPrivacyCurrentBase();
    // FIDA 주문 수신 처리 — 멱등 (같은 날짜+종목 동일 내용이면 200, 다른 내용이면 PrivacyTradeConflictException→409)
    PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command);
}
