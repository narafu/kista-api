package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyTest {

    @Test
    void requireNotArchived_throws_for_archived() {
        assertThatThrownBy(() -> strategy(StrategyStatus.ARCHIVED).requireNotArchived())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("보관된 전략은 변경할 수 없습니다.");
        assertThatCode(() -> strategy(StrategyStatus.ACTIVE).requireNotArchived()).doesNotThrowAnyException();
        assertThatCode(() -> strategy(StrategyStatus.PAUSED).requireNotArchived()).doesNotThrowAnyException();
        assertThat(strategy(StrategyStatus.ARCHIVED).isArchived()).isTrue();
    }

    private static Strategy strategy(StrategyStatus status) {
        return new Strategy(UUID.randomUUID(), UUID.randomUUID(), StrategyType.INFINITE, status,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }
}
