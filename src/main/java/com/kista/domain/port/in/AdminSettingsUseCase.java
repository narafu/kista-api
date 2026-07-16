package com.kista.domain.port.in;

import com.kista.domain.model.settings.RuntimeSettings;

import java.util.UUID;

public interface AdminSettingsUseCase {
    RuntimeSettings getSettings(); // 관리자 런타임 설정 조회

    RuntimeSettings updateSettings(UUID adminId, RuntimeSettings settings); // 전체 설정 원자적 갱신
}
