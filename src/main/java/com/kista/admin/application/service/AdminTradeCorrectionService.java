package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.contract.trading.TradeCorrectionRequest;
import com.kista.contract.trading.TradeCorrectionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
    public TradeCorrectionResponse correctManualFills(UUID adminId, TradeCorrectionRequest command) {
        TradeCorrectionResponse result = tradingCommandPort.correctManualFills(command);

        auditLogPort.log(adminId, AUDIT_ACTION, "STRATEGY", result.strategyId(),
                Map.of(
                        "userId", result.userId().toString(),
                        "accountId", result.accountId().toString(),
                        "fills", result.processedCount(),
                        "fillDetails", command.fills().stream().map(AdminTradeCorrectionService::auditFill).toList(),
                        "cycleEnded", result.cycleEnded()
                ));

        return result;
    }

    // 체결 명세 1건의 감사 기록 — 메모·외부 주문번호는 선택값이라 있을 때만 담는다(Map.of는 null 불가)
    private static Map<String, Object> auditFill(TradeCorrectionRequest.Fill fill) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tradeDate", fill.tradeDate().toString());
        entry.put("direction", fill.direction().name());
        entry.put("quantity", fill.quantity());
        entry.put("price", fill.price());
        if (fill.externalOrderId() != null) entry.put("externalOrderId", fill.externalOrderId());
        if (fill.memo() != null) entry.put("memo", fill.memo());
        return entry;
    }
}
