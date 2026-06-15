package com.kista.application.service.privacy;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyServiceTest {

    @Mock PrivacyTradePort privacyTradePort;

    @InjectMocks
    PrivacyService sut;

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
}
