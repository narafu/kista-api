package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.privacy.application.usecase.PrivacyTradeValidationUseCase;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeValidationReport;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrivacyBaseGuardTest {

    @Mock PrivacyTradeValidationUseCase validationService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks PrivacyBaseGuard guard;

    PrivacyTradeBase base = new PrivacyTradeBase(
            UUID.randomUUID(), new BigDecimal("20.00"), 10, BigDecimal.ZERO, new BigDecimal("20.00"), List.of());
    LocalDate today = LocalDate.of(2026, 10, 6);

    PrivacyTradeValidationReport issues() {
        return new PrivacyTradeValidationReport(List.of(new PrivacyTradeValidationReport.Issue(
                PrivacyTradeValidationReport.Severity.WARNING, "MISSING_SELL", "SELL 주문이 없습니다")));
    }

    PrivacyTradeValidationReport clean() {
        return new PrivacyTradeValidationReport(List.of());
    }

    @Test
    void screen_withIssues_returnsNullAndAlerts() {
        when(validationService.inspect(base)).thenReturn(issues());

        assertThat(guard.screen(base, today, "개장 배치")).isNull();

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent t && t.userId() == null && t.message().contains("개장 배치") && t.message().contains("MISSING_SELL")));
    }

    @Test
    void screen_noIssues_returnsSameBaseWithoutAlert() {
        when(validationService.inspect(base)).thenReturn(clean());

        assertThat(guard.screen(base, today, "마감 배치")).isSameAs(base);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void screen_nullBase_returnsNull() {
        assertThat(guard.screen(null, today, "마감 배치")).isNull();

        verifyNoInteractions(validationService, eventPublisher);
    }

    @Test
    void screen_inspectThrows_failsClosedAndAlerts() {
        when(validationService.inspect(base)).thenThrow(new IllegalStateException("db down"));

        assertThat(guard.screen(base, today, "마감 배치")).isNull();

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent t && t.userId() == null && t.message().contains("점검 실패")));
    }

    @Test
    void usable_variants() {
        when(validationService.inspect(base)).thenReturn(clean(), issues());

        assertThat(guard.usable(null)).isFalse();
        assertThat(guard.usable(base)).isTrue();
        assertThat(guard.usable(base)).isFalse();
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void usable_inspectThrows_returnsFalseWithoutAlert() {
        when(validationService.inspect(base)).thenThrow(new IllegalStateException("db down"));

        assertThat(guard.usable(base)).isFalse();

        verifyNoInteractions(eventPublisher);
    }
}
