package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingPolicyPort;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.admin.domain.model.RuntimeSettingsBundle;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.BrokerSettings;
import com.kista.sharedkernel.TradingPolicySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSettingsServiceTest {

    @Mock TradingPolicyPort tradingPolicyPort; // trading-core 정책 위임 대역
    @Mock AuditLogPort auditLogPort; // 감사 로그 대역
    private final RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class); // root 설정 트랜잭션 경계 대역

    private AdminSettingsService service; // 테스트 대상

    @BeforeEach
    void setUp() {
        service = new AdminSettingsService(runtimeSettingsService, tradingPolicyPort, auditLogPort);
    }

    @Test
    void getSettings_combinesRootSettingsAndTradingPolicy() {
        RuntimeSettings runtime = new RuntimeSettings(false, null);
        TradingPolicySettings policy = withBroker(Broker.TOSS, false);
        when(runtimeSettingsService.load()).thenReturn(runtime);
        when(tradingPolicyPort.load()).thenReturn(policy);

        RuntimeSettingsBundle bundle = service.getSettings();

        assertThat(bundle.runtime()).isEqualTo(runtime);
        assertThat(bundle.tradingPolicy()).isEqualTo(policy);
    }

    @Test
    void updateSettings_replacesTradingPolicyBeforeRootAndLogsCombinedDiff() {
        UUID adminId = UUID.randomUUID();
        RuntimeSettings previousRuntime = RuntimeSettings.defaults();
        RuntimeSettings requestedRuntime = new RuntimeSettings(false, previousRuntime.benchmarks());
        TradingPolicySettings previousPolicy = TradingPolicySettings.defaults();
        TradingPolicySettings requestedPolicy = withBroker(Broker.TOSS, false);
        when(tradingPolicyPort.load()).thenReturn(previousPolicy);
        when(tradingPolicyPort.replace(requestedPolicy)).thenReturn(requestedPolicy);
        when(runtimeSettingsService.update(requestedRuntime, true))
                .thenReturn(new RuntimeSettingsService.Updated(previousRuntime, requestedRuntime));

        RuntimeSettingsBundle saved = service.updateSettings(adminId,
                new RuntimeSettingsBundle(requestedRuntime, requestedPolicy), true);

        assertThat(saved.runtime()).isEqualTo(requestedRuntime);
        assertThat(saved.tradingPolicy()).isEqualTo(requestedPolicy);
        // 정책 소유자(trading-core) 반영이 root 저장보다 먼저 — 거절되면 root를 건드리지 않기 위한 순서
        InOrder order = inOrder(tradingPolicyPort, runtimeSettingsService);
        order.verify(tradingPolicyPort).replace(requestedPolicy);
        order.verify(runtimeSettingsService).update(requestedRuntime, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogPort).log(eq(adminId), eq("RUNTIME_SETTINGS_UPDATE"), eq("RUNTIME_SETTINGS"), isNull(), payload.capture());
        assertThat(payload.getValue()).containsEntry("approvalRequired", false);
        assertThat(payload.getValue()).containsEntry("brokers", Map.of("TOSS", false));
        assertThat(payload.getValue()).doesNotContainKey("strategies");
    }

    @Test
    void updateSettings_whenTradingPolicyRejected_leavesRootSettingsUntouched() {
        UUID adminId = UUID.randomUUID();
        RuntimeSettingsBundle requested = RuntimeSettingsBundle.defaults();
        when(tradingPolicyPort.load()).thenReturn(TradingPolicySettings.defaults());
        when(tradingPolicyPort.replace(any())).thenThrow(new IllegalArgumentException("정책 거절"));

        assertThatThrownBy(() -> service.updateSettings(adminId, requested, true))
                .isInstanceOf(IllegalArgumentException.class);

        verify(runtimeSettingsService, never()).update(any(), anyBoolean());
        verifyNoInteractions(auditLogPort);
    }

    private static TradingPolicySettings withBroker(Broker broker, boolean enabled) {
        TradingPolicySettings defaults = TradingPolicySettings.defaults();
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(defaults.brokers());
        brokers.put(broker, new BrokerSettings(enabled));
        return new TradingPolicySettings(brokers, defaults.strategies());
    }
}
