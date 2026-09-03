package com.kista.privacy.application.service;

import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.FidaPlannedOrder;
import com.kista.privacy.domain.model.PrivacyOrderDirection;
import com.kista.privacy.domain.model.PrivacyOrderType;
import com.kista.privacy.domain.model.PrivacyTradeSaveResult;
import com.kista.privacy.domain.model.PrivacyTradeValidationReport;
import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyServiceTest {

    @Mock PrivacyTradePort privacyTradePort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PrivacyTradeValidationService validationService;

    @InjectMocks
    PrivacyService sut;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(validationService.inspect(any(FidaOrderCommand.class)))
                .thenReturn(PrivacyTradeValidationReport.empty());
    }

    @Test
    void executeFidaOrder_delegates_to_privacyTradePort() {
        UUID baseId = UUID.randomUUID();
        LocalDate receivedDate = LocalDate.now(); // FIDA 송신값 (KST 발행일 원본)
        FidaOrderCommand req = new FidaOrderCommand(
                receivedDate, StrategyTicker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        when(privacyTradePort.saveBaseWithOrders(any())).thenReturn(new PrivacyTradeSaveResult(baseId, true));

        PrivacyTradeSaveResult result = sut.executeFidaOrder(req);

        assertThat(result.id()).isEqualTo(baseId);
        assertThat(result.created()).isTrue();
        // FIDA 수신 날짜(KST 발행일)가 변환 없이 그대로 port에 전달된다
        verify(privacyTradePort).saveBaseWithOrders(
                argThat(r -> r.releaseDate().equals(receivedDate)));
    }

    @Test
    void executeFidaOrder_returns_existing_when_not_created() {
        UUID baseId = UUID.randomUUID();
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.now(), StrategyTicker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        when(privacyTradePort.saveBaseWithOrders(any())).thenReturn(new PrivacyTradeSaveResult(baseId, false));

        PrivacyTradeSaveResult result = sut.executeFidaOrder(req);

        assertThat(result.created()).isFalse();
    }

    @Test
    void executeFidaOrder_warnsWhenSellIsMissingButStillSaves() {
        UUID baseId = UUID.randomUUID();
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.of(2026, 6, 30), StrategyTicker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 4,
                List.of(
                        new FidaPlannedOrder(PrivacyOrderDirection.BUY, PrivacyOrderType.LIMIT, 2, new BigDecimal("234.46")),
                        new FidaPlannedOrder(PrivacyOrderDirection.BUY, PrivacyOrderType.LIMIT, 2, new BigDecimal("233.84"))));

        when(validationService.inspect(any(FidaOrderCommand.class)))
                .thenReturn(PrivacyTradeValidationReport.warning("MISSING_SELL", "SELL 주문이 없습니다"));
        when(privacyTradePort.saveBaseWithOrders(any())).thenReturn(new PrivacyTradeSaveResult(baseId, true));

        PrivacyTradeSaveResult result = sut.executeFidaOrder(req);

        assertThat(result.id()).isEqualTo(baseId);
        verify(eventPublisher).publishEvent(argThat((PrivacyAlertRaisedEvent p) ->
                p.severity() == PrivacyAlertRaisedEvent.Severity.WARNING
                        && p.message().contains("MISSING_SELL")));
        verify(privacyTradePort).saveBaseWithOrders(any());
    }

    @Test
    void executeFidaOrder_rejectsWhenExplicitSellExceedsHoldings() {
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.of(2026, 6, 30), StrategyTicker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 2,
                List.of(new FidaPlannedOrder(PrivacyOrderDirection.SELL, PrivacyOrderType.LIMIT, 4, new BigDecimal("236.54"))));

        when(validationService.inspect(any(FidaOrderCommand.class)))
                .thenReturn(PrivacyTradeValidationReport.blocking("EXPLICIT_SELL_EXCEEDS_HOLDINGS", "매도 수량 초과"));

        assertThatThrownBy(() -> sut.executeFidaOrder(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPLICIT_SELL_EXCEEDS_HOLDINGS");

        verify(eventPublisher).publishEvent(argThat((PrivacyAlertRaisedEvent p) ->
                p.severity() == PrivacyAlertRaisedEvent.Severity.BLOCKING
                        && p.message().contains("[FIDA]")
                        && p.message().contains("EXPLICIT_SELL_EXCEEDS_HOLDINGS")));
        verify(privacyTradePort, never()).saveBaseWithOrders(any());
    }
}
