package com.kista.application.service.admin;

import com.kista.common.TradeDateConverter;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminQueryServiceTest {

    @Mock UserPort userPort;
    @Mock AccountPort accountPort;
    @Mock OrderPort orderPort;
    @Mock AuditLogPort auditLogPort;
    @Mock StrategyPort strategyPort;
    @Mock PrivacyTradePort privacyTradePort;

    @InjectMocks AdminQueryService service;

    @Test
    void listPrivacyBases_null이면_EPOCH부터_조회() {
        when(privacyTradePort.findBasesFromTradeDate(LocalDate.EPOCH)).thenReturn(List.of());

        service.listPrivacyBases(null);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(privacyTradePort).findBasesFromTradeDate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.EPOCH);
    }

    @Test
    void listPrivacyBases_30일이면_KST_30일전을_UTC로_변환해_조회() {
        when(privacyTradePort.findBasesFromTradeDate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        service.listPrivacyBases(30);

        LocalDate expected = TradeDateConverter.toUtc(LocalDate.now().minusDays(30));
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(privacyTradePort).findBasesFromTradeDate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(expected);
    }
}
