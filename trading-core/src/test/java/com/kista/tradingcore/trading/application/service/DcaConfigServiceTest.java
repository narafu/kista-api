package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.*;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVersionPort;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DcaConfigServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyDcaDetailPort dcaDetailPort;

    DcaConfigService service;

    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DcaConfigService(strategyPort, accountPort, strategyVersionPort, dcaDetailPort);
    }

    private static DcaConfig config(OrderDirection direction, String amount) {
        return new DcaConfig(direction, new BigDecimal(amount), OrderType.MOC, DcaFrequency.DAILY, null, null);
    }

    private static Strategy strategy(StrategyType type) {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, type, StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
    }

    @Test
    void updateConfig_createsNewVersionAndDetail() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(config(OrderDirection.BUY, "300")));
        UUID newVersionId = UUID.randomUUID();
        when(strategyVersionPort.nextVersionNo(STRATEGY_ID)).thenReturn(2);
        when(strategyVersionPort.save(any())).thenReturn(new StrategyVersion(newVersionId, STRATEGY_ID, 2, null, null));

        service.updateConfig(STRATEGY_ID, USER_ID, config(OrderDirection.BUY, "500"));

        // 버전 번호 조회는 soft delete 이전이어야 한다
        InOrder order = inOrder(strategyVersionPort, dcaDetailPort);
        order.verify(strategyVersionPort).nextVersionNo(STRATEGY_ID);
        order.verify(strategyVersionPort).softDeleteActiveByStrategyId(eq(STRATEGY_ID), any());
        order.verify(dcaDetailPort).save(newVersionId, config(OrderDirection.BUY, "500"));
    }

    @Test
    void updateConfig_rejectsDirectionChange() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(config(OrderDirection.BUY, "300")));

        assertThatThrownBy(() -> service.updateConfig(STRATEGY_ID, USER_ID, config(OrderDirection.SELL, "300")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(strategyVersionPort, never()).softDeleteActiveByStrategyId(any(), any());
    }

    @Test
    void updateConfig_rejectsNonDcaStrategy() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.INFINITE));

        assertThatThrownBy(() -> service.updateConfig(STRATEGY_ID, USER_ID, config(OrderDirection.BUY, "300")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dcaDetailPort);
    }
}
