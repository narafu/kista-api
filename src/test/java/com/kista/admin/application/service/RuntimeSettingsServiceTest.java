package com.kista.admin.application.service;

import com.kista.admin.domain.model.BenchmarkFieldSettings;
import com.kista.admin.domain.model.BenchmarkSettings;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.sharedkernel.Broker;
import com.kista.user.domain.model.User;
import com.kista.user.application.usecase.UserUseCase;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.RuntimeSettingsPort;
import com.kista.user.application.port.output.UserPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyDefaults;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

@ExtendWith(MockitoExtension.class)
class RuntimeSettingsServiceTest {

    @Mock RuntimeSettingsPort settingsPort; // 설정 저장소 대역
    @Mock UserPort userPort; // 승인 대기 사용자 조회 대역
    @Mock UserUseCase userUseCase; // 기존 사용자 승인 흐름 대역
    @Mock AuditLogPort auditLogPort; // 감사 로그 대역

    private RuntimeSettingsService service; // 테스트 대상

    @BeforeEach
    void setUp() {
        service = new RuntimeSettingsService(settingsPort, userPort, userUseCase, auditLogPort);
    }

    @Test
    void getSettings_loadsCurrentSettings() {
        RuntimeSettings settings = RuntimeSettings.defaults();
        when(settingsPort.load()).thenReturn(settings);

        assertThat(service.getSettings()).isEqualTo(settings);
    }

    @Test
    void updateSettings_whenApprovalTurnsOff_savesOnceAndApprovesOnlyPendingUsers() {
        UUID adminId = UUID.randomUUID();
        User pending = user(UserStatus.PENDING);
        RuntimeSettings previous = RuntimeSettings.defaults();
        RuntimeSettings updated = new RuntimeSettings(false, previous.brokers(), previous.strategies());
        when(settingsPort.loadForUpdate()).thenReturn(previous);
        when(settingsPort.save(updated)).thenReturn(updated);
        when(userPort.findAllByStatus(UserStatus.PENDING)).thenReturn(List.of(pending));

        assertThat(service.updateSettings(adminId, updated, true)).isEqualTo(updated);

        verify(settingsPort, times(1)).save(updated);
        verify(userPort).findAllByStatus(UserStatus.PENDING);
        verify(userUseCase).approve(pending.id());
        verify(userPort, never()).findAllByStatus(UserStatus.REJECTED);
        verify(auditLogPort).log(eq(adminId), eq("RUNTIME_SETTINGS_UPDATE"), eq("RUNTIME_SETTINGS"),
                isNull(), anyMap());
    }

    @Test
    void updateSettings_whenBenchmarksOmitted_keepsPreviousBenchmarks() {
        UUID adminId = UUID.randomUUID();
        BenchmarkSettings customBenchmarks = new BenchmarkSettings(
                new BenchmarkFieldSettings<>(List.of("VOO", "TQQQ"), "VOO"));
        RuntimeSettings previous = new RuntimeSettings(true, RuntimeSettings.defaults().brokers(),
                RuntimeSettings.defaults().strategies(), customBenchmarks);
        // 요청 DTO에 benchmarks가 없었던 상황을 재현 — toDomain()이 이미 null을 defaults()로 치환한 상태
        RuntimeSettings requested = new RuntimeSettings(false, previous.brokers(), previous.strategies(), null);
        RuntimeSettings expectedSaved = new RuntimeSettings(false, previous.brokers(), previous.strategies(),
                customBenchmarks);
        when(settingsPort.loadForUpdate()).thenReturn(previous);
        when(settingsPort.save(expectedSaved)).thenReturn(expectedSaved);

        RuntimeSettings saved = service.updateSettings(adminId, requested, false);

        assertThat(saved.benchmarks()).isEqualTo(customBenchmarks);
        verify(settingsPort).save(expectedSaved);
        verify(settingsPort, never()).save(requested);
    }

    @Test
    void approvalRequiredForUpdate_delegatesToLoadForUpdate() {
        when(settingsPort.loadForUpdate()).thenReturn(settingsWithApprovalRequired(true));

        boolean result = service.approvalRequiredForUpdate();

        assertThat(result).isTrue();
        verify(settingsPort).loadForUpdate();
    }

    @Test
    @DisplayName("enabled는 저장된 브로커 설정을 그대로 반환한다")
    void enabled_delegatesToLoadedSettings() {
        when(settingsPort.load()).thenReturn(settingsWithBroker(Broker.KIS, false));

        boolean result = service.enabled(Broker.KIS);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("find() — 활성 전략 타입의 설정을 trading 소유 타입으로 매핑해 반환한다")
    void find_mapsAdminSettingsToTradingType() {
        RuntimeSettings settings = RuntimeSettings.defaults();
        when(settingsPort.load()).thenReturn(settings);

        Optional<com.kista.trading.domain.strategy.StrategyCreationSettings> result =
                service.find(StrategyType.INFINITE);

        assertThat(result).isPresent();
        assertThat(result.get().enabled()).isTrue();
        assertThat(result.get().divisionCount().defaultValue()).isEqualTo(StrategyDefaults.DEFAULT_DIVISION_COUNT);
    }

    @Test
    void updateSettings_whenApprovalRemainsOff_doesNotApproveUsersAgain() {
        UUID adminId = UUID.randomUUID();
        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettings disabled = new RuntimeSettings(false, defaults.brokers(), defaults.strategies());
        when(settingsPort.loadForUpdate()).thenReturn(disabled);
        when(settingsPort.save(disabled)).thenReturn(disabled);

        service.updateSettings(adminId, disabled, true);

        verifyNoInteractions(userPort, userUseCase);
    }

    private User user(UserStatus status) {
        return new User(UUID.randomUUID(), "kakao", "nickname", null, status, UserRole.USER,
                null, null, null, null, null, User.DEFAULT_CHANNEL);
    }

    private RuntimeSettings settingsWithApprovalRequired(boolean approvalRequired) {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        return new RuntimeSettings(approvalRequired, defaults.brokers(), defaults.strategies());
    }

    private RuntimeSettings settingsWithBroker(Broker broker, boolean enabled) {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        // 지정된 broker의 enabled 상태를 변경한 새 설정 반환
        var brokers = new java.util.EnumMap<>(defaults.brokers());
        brokers.put(broker, new RuntimeSettings.BrokerSettings(enabled));
        return new RuntimeSettings(defaults.approvalRequired(), brokers, defaults.strategies());
    }
}
