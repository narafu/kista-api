package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.StrategyStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 실제 수동 체결 보정 로직은 trading-core로 이관됨 — 이 테스트는 포트 위임과 감사 로그 호출만 검증
@ExtendWith(MockitoExtension.class)
class AdminTradeCorrectionServiceTest {

    @Mock TradingCommandPort tradingCommandPort;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminTradeCorrectionService service;

    private static final UUID ADMIN_ID    = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID ACCOUNT_ID  = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    @Test
    void correctManualFills_요청을_포트로_그대로_전달하고_응답을_되돌리고_감사로그를_남긴다() {
        AdminManualTradeCorrectionCommand command = new AdminManualTradeCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID,
                List.of(new AdminManualTradeCorrectionCommand.Fill(
                        LocalDate.of(2026, 7, 1), OrderDirection.SELL, 2,
                        new BigDecimal("267.37"), "MANUAL-1", "manual correction")));
        AdminTradeCorrectionResult result = new AdminTradeCorrectionResult(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, 1, 0,
                new BigDecimal("266.65"), new BigDecimal("7200.05"),
                StrategyStatus.PAUSED, true, LocalDate.of(2026, 7, 1));
        when(tradingCommandPort.correctManualFills(any())).thenReturn(result);

        AdminTradeCorrectionResult response = service.correctManualFills(ADMIN_ID, command);

        ArgumentCaptor<AdminManualTradeCorrectionCommand> captor = ArgumentCaptor.forClass(AdminManualTradeCorrectionCommand.class);
        verify(tradingCommandPort).correctManualFills(captor.capture());
        AdminManualTradeCorrectionCommand sent = captor.getValue();
        assertThat(sent).isEqualTo(command);

        assertThat(response).isEqualTo(result);

        verify(auditLogPort).log(eq(ADMIN_ID), eq("TRADE_MANUAL_CORRECTION"), eq("STRATEGY"), eq(STRATEGY_ID), any(Map.class));
    }
}
