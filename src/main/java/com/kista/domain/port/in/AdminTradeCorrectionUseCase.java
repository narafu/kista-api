package com.kista.domain.port.in;

import com.kista.domain.model.admin.AdminManualTradeCorrectionCommand;
import com.kista.domain.model.admin.AdminTradeCorrectionResult;

import java.util.UUID;

// 관리자 수동 체결 보정 — 사용자/계좌/전략 선택 후 다건 체결을 원자적으로 반영
public interface AdminTradeCorrectionUseCase {
    AdminTradeCorrectionResult correctManualFills(UUID adminId, AdminManualTradeCorrectionCommand command);
}
