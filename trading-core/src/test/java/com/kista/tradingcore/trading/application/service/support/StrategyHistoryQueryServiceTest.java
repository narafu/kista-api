package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyHistoryQueryServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock CyclePositionPort cyclePositionPort;

    @Test
    void getByStrategy_krwAccount_roundsDepositToScale0() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Strategy strategy = new Strategy(UUID.randomUUID(), accountId, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategy.id())).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));
        when(cyclePositionPort.findByStrategyIdWithCursor(eq(strategy.id()), any(), any(), eq(11)))
                .thenReturn(List.of(new CyclePositionHistoryEntry(UUID.randomUUID(), accountId, StrategyTicker.BTC, StrategyType.DCA,
                        new BigDecimal("12345.67"), new BigDecimal("0.55"), new BigDecimal("0.53"), BigDecimal.TEN, Instant.now())));
        // 이력 조회에 쓰지 않는 협력 객체는 null
        var service = new StrategyHistoryQueryService(accountPort, strategyPort, cyclePositionPort,
                null, null, null, null, null, FixedClockTestConfig.FIXED);

        var page = service.getByStrategy(strategy.id(), userId, null, null, null, 10);

        assertThat(page.items().getFirst().deposit()).hasToString("12346");
    }
}
