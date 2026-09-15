package com.kista.admin.application.service;

import com.kista.sharedkernel.OrderStatus;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 실제 재주문 로직은 trading-core로 이관됨 — 이 테스트는 포트 위임과 감사 로그 호출만 검증
@ExtendWith(MockitoExtension.class)
class AdminReorderServiceTest {

    @Mock TradingCommandPort tradingCommandPort;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminReorderService service;

    private static final UUID ADMIN_ID    = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID ACCOUNT_ID  = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID ORDER_ID    = UUID.randomUUID();

    @Test
    void reorder_요청을_포트로_그대로_전달하고_응답을_되돌리고_감사로그를_남긴다() {
        AdminReorderCommand command = new AdminReorderCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                OrderTiming.AT_CLOSE, LocalDate.of(2026, 7, 1), OrderDirection.SELL,
                2, new BigDecimal("250.00"), "reorder memo");
        AdminReorderResult result = new AdminReorderResult(USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                OrderStatus.PLANNED, OrderStatus.PLANNED, null,
                new BigDecimal("236.54"), 1, OrderDirection.SELL);
        when(tradingCommandPort.reorder(any())).thenReturn(result);

        AdminReorderResult response = service.reorder(ADMIN_ID, command);

        ArgumentCaptor<AdminReorderCommand> captor = ArgumentCaptor.forClass(AdminReorderCommand.class);
        verify(tradingCommandPort).reorder(captor.capture());
        AdminReorderCommand sent = captor.getValue();
        assertThat(sent).isEqualTo(command);

        assertThat(response).isEqualTo(result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogPort).log(eq(ADMIN_ID), eq("REORDER"), eq("ORDER"), eq(ORDER_ID), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        // 실제 브로커 주문을 유발하는 감사 대상이므로 원본/변경 주문 내역이 전부 남아야 한다
        assertThat(payload.get("oldStatus")).isEqualTo("PLANNED");
        assertThat(payload.get("oldPrice")).isEqualTo("236.54");
        assertThat(payload.get("oldQuantity")).isEqualTo(1);
        assertThat(payload.get("newDirection")).isEqualTo("SELL");
        assertThat(payload.get("newPrice")).isEqualTo("250.00");
        assertThat(payload.get("newQuantity")).isEqualTo(2);
    }
}
