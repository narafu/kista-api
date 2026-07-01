package com.kista.application.service.privacy;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.PrivacyTradePort;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyServiceTest {

    @Mock PrivacyTradePort privacyTradePort;
    @Mock NotifyPort notifyPort;
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
        LocalDate utcDate = LocalDate.now(); // FIDA 송신값 (UTC=US 거래일)
        FidaOrderCommand req = new FidaOrderCommand(
                utcDate, Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        when(privacyTradePort.saveBaseWithOrders(any())).thenReturn(new PrivacyTradeSaveResult(baseId, true));

        PrivacyTradeSaveResult result = sut.executeFidaOrder(req);

        assertThat(result.id()).isEqualTo(baseId);
        assertThat(result.created()).isTrue();
        // FIDA UTC → KST 변환된 새 request로 포트 호출됨
        verify(privacyTradePort).saveBaseWithOrders(
                argThat(r -> r.tradeDate().equals(TradeDateConverter.toKst(utcDate))));
    }

    @Test
    void executeFidaOrder_returns_existing_when_not_created() {
        UUID baseId = UUID.randomUUID();
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.now(), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        when(privacyTradePort.saveBaseWithOrders(any())).thenReturn(new PrivacyTradeSaveResult(baseId, false));

        PrivacyTradeSaveResult result = sut.executeFidaOrder(req);

        assertThat(result.created()).isFalse();
    }

    @Test
    void executeFidaOrder_warnsWhenSellIsMissingButStillSaves() {
        UUID baseId = UUID.randomUUID();
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.of(2026, 6, 30), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 4,
                List.of(
                        Order.planned(LocalDate.of(2026, 6, 30), Ticker.SOXL, Order.OrderType.LIMIT,
                                Order.OrderDirection.BUY, 2, new BigDecimal("234.46")),
                        Order.planned(LocalDate.of(2026, 6, 30), Ticker.SOXL, Order.OrderType.LIMIT,
                                Order.OrderDirection.BUY, 2, new BigDecimal("233.84"))));

        when(validationService.inspect(any(FidaOrderCommand.class)))
                .thenReturn(PrivacyTradeValidationReport.warning("MISSING_SELL", "SELL 주문이 없습니다"));
        when(privacyTradePort.saveBaseWithOrders(any())).thenReturn(new PrivacyTradeSaveResult(baseId, true));

        PrivacyTradeSaveResult result = sut.executeFidaOrder(req);

        assertThat(result.id()).isEqualTo(baseId);
        verify(notifyPort).notifyInfo(org.mockito.ArgumentMatchers.contains("MISSING_SELL"));
        verify(privacyTradePort).saveBaseWithOrders(any());
    }

    @Test
    void executeFidaOrder_rejectsWhenExplicitSellExceedsHoldings() {
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.of(2026, 6, 30), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 2,
                List.of(Order.planned(LocalDate.of(2026, 6, 30), Ticker.SOXL, Order.OrderType.LIMIT,
                        Order.OrderDirection.SELL, 4, new BigDecimal("236.54"))));

        when(validationService.inspect(any(FidaOrderCommand.class)))
                .thenReturn(PrivacyTradeValidationReport.blocking("EXPLICIT_SELL_EXCEEDS_HOLDINGS", "매도 수량 초과"));

        assertThatThrownBy(() -> sut.executeFidaOrder(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPLICIT_SELL_EXCEEDS_HOLDINGS");

        verify(notifyPort).notifyError(any(IllegalArgumentException.class));
        verify(privacyTradePort, never()).saveBaseWithOrders(any());
    }
}
