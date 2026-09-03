package com.kista.strategyconfig.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveStrategyCountAdapterTest {

    @Mock
    private AccountPort accountPort;
    @Mock
    private StrategyPort strategyPort;

    private ActiveStrategyCountAdapter adapter;

    @Test
    void countActiveByUserId_사용자_전_계좌의_ACTIVE_전략만_합산한다() {
        adapter = new ActiveStrategyCountAdapter(accountPort, strategyPort);
        UUID userId = UUID.randomUUID();
        UUID accountId1 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        // mockAccount() 내부에 완결된 when()...thenReturn()이 있어 outer when()의 인자로 바로
        // 중첩 호출하면 Mockito의 ongoing-stubbing 상태가 꼬여 UnfinishedStubbingException 발생 —
        // 로컬 변수로 먼저 완결시킨 뒤 참조해야 한다
        Account account1 = mockAccount(accountId1);
        Account account2 = mockAccount(accountId2);
        when(accountPort.findByUserId(userId)).thenReturn(List.of(account1, account2));
        when(strategyPort.findByAccountId(accountId1)).thenReturn(List.of(
                strategy(accountId1, StrategyStatus.ACTIVE), strategy(accountId1, StrategyStatus.PAUSED)));
        when(strategyPort.findByAccountId(accountId2)).thenReturn(List.of(
                strategy(accountId2, StrategyStatus.ACTIVE)));

        long count = adapter.countActiveByUserId(userId);

        assertThat(count).isEqualTo(2);
    }

    private Account mockAccount(UUID id) {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.id()).thenReturn(id);
        return account;
    }

    private Strategy strategy(UUID accountId, StrategyStatus status) {
        return new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, status,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }
}
