package com.kista.application.service.settings;

import com.kista.domain.model.settings.BenchmarkFieldSettings;
import com.kista.domain.model.settings.BenchmarkSettings;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.RuntimeSettingsPort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
        User pending = user(User.UserStatus.PENDING);
        RuntimeSettings previous = RuntimeSettings.defaults();
        RuntimeSettings updated = new RuntimeSettings(false, previous.brokers(), previous.strategies());
        when(settingsPort.loadForUpdate()).thenReturn(previous);
        when(settingsPort.save(updated)).thenReturn(updated);
        when(userPort.findAllByStatus(User.UserStatus.PENDING)).thenReturn(List.of(pending));

        assertThat(service.updateSettings(adminId, updated, true)).isEqualTo(updated);

        verify(settingsPort, times(1)).save(updated);
        verify(userPort).findAllByStatus(User.UserStatus.PENDING);
        verify(userUseCase).approve(pending.id());
        verify(userPort, never()).findAllByStatus(User.UserStatus.REJECTED);
        verify(auditLogPort).log(eq(adminId), eq("RUNTIME_SETTINGS_UPDATE"), eq("RUNTIME_SETTINGS"),
                isNull(), anyMap());
    }

    @Test
    void updateSettings_whenBenchmarksOmitted_keepsPreviousBenchmarksInsteadOfDefaults() {
        UUID adminId = UUID.randomUUID();
        BenchmarkSettings customBenchmarks = new BenchmarkSettings(
                new BenchmarkFieldSettings<>(List.of("VOO", "TQQQ"), "VOO"));
        RuntimeSettings previous = new RuntimeSettings(true, RuntimeSettings.defaults().brokers(),
                RuntimeSettings.defaults().strategies(), customBenchmarks);
        // 요청 DTO에 benchmarks가 없었던 상황을 재현 — toDomain()이 이미 null을 defaults()로 치환한 상태
        RuntimeSettings requested = new RuntimeSettings(false, previous.brokers(), previous.strategies());
        RuntimeSettings expectedSaved = new RuntimeSettings(false, previous.brokers(), previous.strategies(), customBenchmarks);
        when(settingsPort.loadForUpdate()).thenReturn(previous);
        when(settingsPort.save(expectedSaved)).thenReturn(expectedSaved);

        RuntimeSettings saved = service.updateSettings(adminId, requested, false);

        assertThat(saved.benchmarks()).isEqualTo(customBenchmarks);
        verify(settingsPort).save(expectedSaved);
        verify(settingsPort, never()).save(requested);
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

    private User user(User.UserStatus status) {
        return new User(UUID.randomUUID(), "kakao", "nickname", status, User.UserRole.USER,
                null, null, null, null, null, User.DEFAULT_CHANNEL);
    }
}
