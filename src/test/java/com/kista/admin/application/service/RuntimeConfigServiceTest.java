package com.kista.admin.application.service;

import com.kista.admin.application.port.output.TradingPolicyPort;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.admin.domain.model.RuntimeSettingsBundle;
import com.kista.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.BrokerSettings;
import com.kista.sharedkernel.TradingPolicySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeConfigServiceTest {

    @Mock TradingPolicyPort tradingPolicyPort; // trading-core 정책 위임 대역
    private final RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class); // root 설정 대역

    private RuntimeConfigService service; // 테스트 대상

    @BeforeEach
    void setUp() {
        service = new RuntimeConfigService(runtimeSettingsService, tradingPolicyPort);
        when(runtimeSettingsService.load()).thenReturn(new RuntimeSettings(false, null));
    }

    @Test
    void getSettings_combinesRootSettingsAndTradingPolicy() {
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(TradingPolicySettings.defaults().brokers());
        brokers.put(Broker.TOSS, new BrokerSettings(false));
        TradingPolicySettings policy = new TradingPolicySettings(brokers, TradingPolicySettings.defaults().strategies());
        when(tradingPolicyPort.load()).thenReturn(policy);

        RuntimeSettingsBundle bundle = service.getSettings();

        assertThat(bundle.runtime().approvalRequired()).isFalse();
        assertThat(bundle.tradingPolicy()).isEqualTo(policy);
    }

    // trading-core 장애(연결 거부·타임아웃·5xx)는 공개 엔드포인트를 500으로 무너뜨리지 않고 기본 정책으로 강등한다
    @Test
    void getSettings_whenTradingCoreUnreachable_fallsBackToDefaultPolicy() {
        when(tradingPolicyPort.load()).thenThrow(new TradingPolicyUnavailableException("Connection refused", null));

        RuntimeSettingsBundle bundle = service.getSettings();

        assertThat(bundle.runtime().approvalRequired()).isFalse();
        assertThat(bundle.tradingPolicy()).isEqualTo(TradingPolicySettings.defaults());
    }
}
