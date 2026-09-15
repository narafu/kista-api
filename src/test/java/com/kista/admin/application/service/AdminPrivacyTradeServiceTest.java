package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.PrivacyQueryPort;
import com.kista.admin.domain.model.AdminFidaOrderCommand;
import com.kista.admin.domain.model.AdminPrivacyBaseUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;
import com.kista.admin.application.usecase.AdminPrivacyTradeUseCase;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// privacy.application.usecase.PrivacyUseCase/PrivacyTradePort 직접 주입 제거 후 단일 PrivacyQueryPort
// 위임 + 감사 로그 호출만 검증(실제 저장/보정 로직은 trading-core로 완전히 이관됨)
@ExtendWith(MockitoExtension.class)
class AdminPrivacyTradeServiceTest {

    @Mock PrivacyQueryPort privacyQueryPort;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminPrivacyTradeService service;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BASE_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();

    @Test
    void createBase_포트에_위임하고_결과와_감사로그를_남긴다() {
        AdminFidaOrderCommand command = new AdminFidaOrderCommand(LocalDate.of(2026, 6, 10), StrategyTicker.SOXL,
                new BigDecimal("28.50"), BigDecimal.ZERO, null, 0, List.of());
        AdminPrivacyTradeBaseView view = new AdminPrivacyTradeBaseView(BASE_ID, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("28.50"), BigDecimal.ZERO, null, 0, List.of());
        when(privacyQueryPort.createBase(command)).thenReturn(new PrivacyQueryPort.CreateBaseResult(view, true));

        AdminPrivacyTradeUseCase.CreateResult result = service.createBase(ADMIN_ID, command);

        assertThat(result.view()).isEqualTo(view);
        assertThat(result.created()).isTrue();
        verify(auditLogPort).log(eq(ADMIN_ID), eq("PRIVACY_BASE_CREATE"), eq("PRIVACY_TRADE_BASE"), eq(BASE_ID), any());
    }

    @Test
    void updateBase_포트에_위임하고_감사로그를_남긴다() {
        AdminPrivacyBaseUpdateCommand command = new AdminPrivacyBaseUpdateCommand(
                new BigDecimal("30.00"), new BigDecimal("5.00"), new BigDecimal("29.00"), 100);
        AdminPrivacyTradeBaseView view = new AdminPrivacyTradeBaseView(BASE_ID, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("30.00"), new BigDecimal("5.00"), new BigDecimal("29.00"), 100, List.of());
        when(privacyQueryPort.updateBase(BASE_ID, command)).thenReturn(view);

        AdminPrivacyTradeBaseView result = service.updateBase(ADMIN_ID, BASE_ID, command);

        assertThat(result).isEqualTo(view);
        verify(auditLogPort).log(eq(ADMIN_ID), eq("PRIVACY_BASE_UPDATE"), eq("PRIVACY_TRADE_BASE"), eq(BASE_ID), any());
    }

    @Test
    void updateOrder_포트에_위임하고_감사로그를_남긴다() {
        AdminPrivacyOrderUpdateCommand command = new AdminPrivacyOrderUpdateCommand(new BigDecimal("31.00"), 15);
        AdminPrivacyTradeBaseView view = new AdminPrivacyTradeBaseView(BASE_ID, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("30.00"), new BigDecimal("5.00"), new BigDecimal("29.00"), 100, List.of());
        when(privacyQueryPort.updateOrder(BASE_ID, ORDER_ID, command)).thenReturn(view);

        AdminPrivacyTradeBaseView result = service.updateOrder(ADMIN_ID, BASE_ID, ORDER_ID, command);

        assertThat(result).isEqualTo(view);
        verify(auditLogPort).log(eq(ADMIN_ID), eq("PRIVACY_ORDER_UPDATE"), eq("PRIVACY_TRADE_BASE_ORDER"), eq(ORDER_ID), any());
    }
}
