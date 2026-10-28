package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyDisplayOrderTest {

    @Test
    void sorts_by_type_then_ticker_name_then_id() {
        Strategy vr = strategy(StrategyType.VR, StrategyTicker.TQQQ, 1);
        Strategy infiniteSoxl = strategy(StrategyType.INFINITE, StrategyTicker.SOXL, 1);
        Strategy infiniteMagx = strategy(StrategyType.INFINITE, StrategyTicker.MAGX, 2);
        Strategy infiniteMagxEarlier = strategy(StrategyType.INFINITE, StrategyTicker.MAGX, 1);

        assertThat(List.of(vr, infiniteSoxl, infiniteMagx, infiniteMagxEarlier).stream().sorted(Strategy.DISPLAY_ORDER).toList())
                .containsExactly(infiniteMagxEarlier, infiniteMagx, infiniteSoxl, vr);
    }

    private static Strategy strategy(StrategyType type, StrategyTicker ticker, long idSeq) {
        return new Strategy(new UUID(idSeq, 0), UUID.randomUUID(), type, StrategyStatus.ACTIVE, ticker, StrategyCycleSeedType.NONE);
    }
}
