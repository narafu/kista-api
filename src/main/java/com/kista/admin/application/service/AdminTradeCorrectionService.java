package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

// 관리자 수동 체결 보정 — 실제 로직은 trading-core 내부 API로 이관됨, 여기는 요청/응답 전달 + 감사 로그만 담당
@Service
@RequiredArgsConstructor
class AdminTradeCorrectionService implements AdminTradeCorrectionUseCase {

    private static final String AUDIT_ACTION = "TRADE_MANUAL_CORRECTION"; // 감사 로그 액션 코드

    private final TradingCommandPort tradingCommandPort;
    private final AuditLogPort auditLogPort;

    @Override
    public AdminTradeCorrectionResult correctManualFills(UUID adminId, AdminManualTradeCorrectionCommand command) {
        AdminTradeCorrectionResult result = tradingCommandPort.correctManualFills(command);

        auditLogPort.log(adminId, AUDIT_ACTION, "STRATEGY", result.strategyId(),
                Map.of(
                        "userId", result.userId().toString(),
                        "accountId", result.accountId().toString(),
                        "fills", result.processedCount(),
                        "cycleEnded", result.cycleEnded()
                ));

        return result;
    }
}
