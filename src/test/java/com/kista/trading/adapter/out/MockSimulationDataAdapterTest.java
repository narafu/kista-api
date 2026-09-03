package com.kista.trading.adapter.out;

import com.kista.trading.application.port.output.StrategyLookupPort;
import com.kista.broker.domain.model.StrategyRefLite;
import com.kista.trading.domain.model.StrategyRef;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// broker↔strategy-config 순환 해소로 추가된 findStrategiesByAccountId 매핑만 검증 — 나머지 메서드는 기존 통합 테스트가 간접 커버
@ExtendWith(MockitoExtension.class)
class MockSimulationDataAdapterTest {

    @Mock OrderPort orderPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyLookupPort strategyPort;

    private MockSimulationDataAdapter adapter() {
        return new MockSimulationDataAdapter(orderPort, cyclePositionPort, strategyCyclePort, strategyPort);
    }

    @Test
    void findStrategiesByAccountId_mapsStrategyToStrategyRefLite() {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        StrategyRef strategy = new StrategyRef(strategyId, accountId, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(strategy));

        List<StrategyRefLite> result = adapter().findStrategiesByAccountId(accountId);

        assertThat(result).containsExactly(new StrategyRefLite(strategyId, StrategyTicker.TQQQ));
    }
}
