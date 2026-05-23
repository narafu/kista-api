package com.kista.application.service;

import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.privacy.FidaOrderRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FidaOrderServiceTest {

    @Mock PrivacyTradePort privacyTradePort;

    @InjectMocks
    FidaOrderService sut;

    @Test
    void execute_delegates_to_privacyTradePort() {
        UUID masterId = UUID.randomUUID();
        FidaOrderRequest req = new FidaOrderRequest(
                LocalDate.now(), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        when(privacyTradePort.saveMasterWithDetails(any())).thenReturn(new PrivacyTradeSaveResult(masterId, true));

        PrivacyTradeSaveResult result = sut.execute(req);

        assertThat(result.id()).isEqualTo(masterId);
        assertThat(result.created()).isTrue();
        verify(privacyTradePort).saveMasterWithDetails(req);
    }

    @Test
    void execute_returns_existing_when_not_created() {
        UUID masterId = UUID.randomUUID();
        FidaOrderRequest req = new FidaOrderRequest(
                LocalDate.now(), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        when(privacyTradePort.saveMasterWithDetails(any())).thenReturn(new PrivacyTradeSaveResult(masterId, false));

        PrivacyTradeSaveResult result = sut.execute(req);

        assertThat(result.created()).isFalse();
    }
}
