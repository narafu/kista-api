package com.kista.strategyconfig.application.service;

import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.domain.model.StrategyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyLookupAdapterTest {

    @Mock
    private StrategyPort strategyPort;

    private StrategyLookupAdapter adapter;

    @Test
    void findByIdOrThrow은_StrategyRef로_매핑해_반환한다() {
        adapter = new StrategyLookupAdapter(strategyPort);
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Strategy strategy = new Strategy(id, accountId, StrategyType.INFINITE, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(strategyPort.findById(id)).thenReturn(Optional.of(strategy));

        StrategyRef ref = adapter.findByIdOrThrow(id);

        assertThat(ref.id()).isEqualTo(id);
        assertThat(ref.accountId()).isEqualTo(accountId);
        assertThat(ref.isInfinite()).isTrue();
    }

    @Test
    void pause는_전략_상태를_PAUSED로_저장한다() {
        adapter = new StrategyLookupAdapter(strategyPort);
        UUID id = UUID.randomUUID();
        Strategy strategy = new Strategy(id, UUID.randomUUID(), StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(id)).thenReturn(strategy);
        when(strategyPort.save(any(Strategy.class))).thenAnswer(inv -> inv.getArgument(0));

        adapter.pause(id);

        verify(strategyPort).save(argThatPaused());
    }

    private static Strategy argThatPaused() {
        return org.mockito.ArgumentMatchers.argThat(s -> s.status() == StrategyStatus.PAUSED);
    }
}
