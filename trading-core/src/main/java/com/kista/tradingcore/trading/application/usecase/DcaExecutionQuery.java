package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.trading.domain.model.DcaExecutionPage;

import java.util.UUID;

public interface DcaExecutionQuery {
    // DCA 회차 이력 페이지 (거래일 DESC) — 소유권·DCA 타입 검증 포함
    DcaExecutionPage list(UUID strategyId, UUID requesterId, int page, int size);
}
