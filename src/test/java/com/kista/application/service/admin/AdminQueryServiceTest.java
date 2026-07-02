package com.kista.application.service.admin;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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

    @Test
    void listStrategyOrders_전략과_거래일로_주문을_조회한다() {
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        LocalDate tradeDate = LocalDate.of(2026, 7, 1);
        when(orderPort.findByStrategyId(strategyId, tradeDate, tradeDate)).thenReturn(List.of());

        service.listStrategyOrders(strategyId, tradeDate);

        verify(orderPort).findByStrategyId(strategyId, tradeDate, tradeDate);
    }

    @Test
    void findAccount_ID로_단일_계좌를_조회한다() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        Account account = new Account(accountId, UUID.randomUUID(), "test", "74420614",
                null, null, "01", Account.Broker.KIS, null);
        when(accountPort.findById(accountId)).thenReturn(Optional.of(account));

        Optional<Account> result = service.findAccount(accountId);

        assertThat(result).isPresent().contains(account);
        verify(accountPort).findById(accountId);
    }
}
