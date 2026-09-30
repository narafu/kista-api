package com.kista.trading.application.service;

import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.BrokerSettings;
import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyDefaults;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingPolicySettings;
import com.kista.trading.application.port.output.TradingPolicySettingsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingPolicyServiceTest {

    @Mock TradingPolicySettingsPort settingsPort; // 정책 저장소 대역

    private TradingPolicyService service; // 테스트 대상

    @BeforeEach
    void setUp() {
        service = new TradingPolicyService(settingsPort);
    }

    @Test
    void getPolicy_loadsCurrentSettings() {
        TradingPolicySettings settings = TradingPolicySettings.defaults();
        when(settingsPort.load()).thenReturn(settings);

        assertThat(service.getPolicy()).isEqualTo(settings);
    }

    @Test
    void replacePolicy_savesWholeSettings() {
        TradingPolicySettings settings = withBroker(Broker.TOSS, false);
        when(settingsPort.save(settings)).thenReturn(settings);

        assertThat(service.replacePolicy(settings)).isEqualTo(settings);
        verify(settingsPort).save(settings);
    }

    @Test
    @DisplayName("enabled는 저장된 브로커 설정을 그대로 반환한다 — account BrokerEnabledPort 구현")
    void enabled_delegatesToLoadedSettings() {
        when(settingsPort.load()).thenReturn(withBroker(Broker.KIS, false));

        assertThat(service.enabled(Broker.KIS)).isFalse();
        assertThat(service.enabled(Broker.TOSS)).isTrue();
    }

    @Test
    @DisplayName("find()는 전략 타입별 생성 정책을 반환한다 — trading StrategyCreationPolicyPort 구현")
    void find_returnsStrategySettings() {
        when(settingsPort.load()).thenReturn(TradingPolicySettings.defaults());

        Optional<StrategyCreationSettings> result = service.find(StrategyType.INFINITE);

        assertThat(result).isPresent();
        assertThat(result.get().enabled()).isTrue();
        assertThat(result.get().divisionCount().defaultValue()).isEqualTo(StrategyDefaults.DEFAULT_DIVISION_COUNT);
    }

    private static TradingPolicySettings withBroker(Broker broker, boolean enabled) {
        TradingPolicySettings defaults = TradingPolicySettings.defaults();
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(defaults.brokers());
        brokers.put(broker, new BrokerSettings(enabled));
        return new TradingPolicySettings(brokers, defaults.strategies());
    }
}
