package com.kista.admin.application.usecase;

import com.kista.contract.trading.TradeCorrectionRequest;
import com.kista.contract.trading.TradeCorrectionResponse;

import java.util.UUID;

// 관리자 수동 체결 보정 — 사용자/계좌/전략 선택 후 다건 체결을 원자적으로 반영
public interface AdminTradeCorrectionUseCase {
    TradeCorrectionResponse correctManualFills(UUID adminId, TradeCorrectionRequest command);
}
