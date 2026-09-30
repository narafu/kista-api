package com.kista.admin.application.service;

import com.kista.admin.application.port.output.RuntimeSettingsPort;
import com.kista.admin.domain.model.BenchmarkFieldSettings;
import com.kista.admin.domain.model.BenchmarkSettings;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.user.application.event.ApprovalRequirementDisabledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeSettingsServiceTest {

    @Mock RuntimeSettingsPort settingsPort; // 설정 저장소 대역
    @Mock ApplicationEventPublisher eventPublisher; // 커밋 후 이벤트 발행 대역

    private RuntimeSettingsService service; // 테스트 대상

    @BeforeEach
    void setUp() {
        service = new RuntimeSettingsService(settingsPort, eventPublisher);
    }

    @Test
    void load_returnsCurrentSettings() {
        RuntimeSettings settings = RuntimeSettings.defaults();
        when(settingsPort.load()).thenReturn(settings);

        assertThat(service.load()).isEqualTo(settings);
    }

    @Test
    void update_whenApprovalTurnsOff_savesOnceAndPublishesApprovalDisabledEvent() {
        RuntimeSettings previous = RuntimeSettings.defaults();
        RuntimeSettings updated = new RuntimeSettings(false, previous.benchmarks());
        when(settingsPort.loadForUpdate()).thenReturn(previous);
        when(settingsPort.save(updated)).thenReturn(updated);

        RuntimeSettingsService.Updated result = service.update(updated, true);

        assertThat(result.previous()).isEqualTo(previous);
        assertThat(result.saved()).isEqualTo(updated);
        verify(settingsPort, times(1)).save(updated);
        verify(eventPublisher).publishEvent(any(ApprovalRequirementDisabledEvent.class));
    }

    @Test
    void update_whenBenchmarksOmitted_keepsPreviousBenchmarks() {
        BenchmarkSettings customBenchmarks = new BenchmarkSettings(
                new BenchmarkFieldSettings<>(List.of("VOO", "TQQQ"), "VOO"));
        RuntimeSettings previous = new RuntimeSettings(true, customBenchmarks);
        // 요청 DTO에 benchmarks가 없었던 상황을 재현 — toDomain()이 이미 null을 defaults()로 치환한 상태
        RuntimeSettings requested = new RuntimeSettings(false, null);
        RuntimeSettings expectedSaved = new RuntimeSettings(false, customBenchmarks);
        when(settingsPort.loadForUpdate()).thenReturn(previous);
        when(settingsPort.save(expectedSaved)).thenReturn(expectedSaved);

        RuntimeSettingsService.Updated result = service.update(requested, false);

        assertThat(result.saved().benchmarks()).isEqualTo(customBenchmarks);
        verify(settingsPort).save(expectedSaved);
        verify(settingsPort, never()).save(requested);
    }

    @Test
    void approvalRequiredForUpdate_delegatesToLoadForUpdate() {
        when(settingsPort.loadForUpdate()).thenReturn(RuntimeSettings.defaults());

        assertThat(service.approvalRequiredForUpdate()).isTrue();
        verify(settingsPort).loadForUpdate();
    }

    @Test
    void update_whenApprovalRemainsOff_doesNotPublishEvent() {
        RuntimeSettings disabled = new RuntimeSettings(false, BenchmarkSettings.defaults());
        when(settingsPort.loadForUpdate()).thenReturn(disabled);
        when(settingsPort.save(disabled)).thenReturn(disabled);

        service.update(disabled, true);

        verifyNoInteractions(eventPublisher);
    }
}
