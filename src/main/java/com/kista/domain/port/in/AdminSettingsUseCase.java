package com.kista.domain.port.in;

import com.kista.domain.model.settings.RuntimeSettings;

import java.util.UUID;

public interface AdminSettingsUseCase {
    RuntimeSettings getSettings(); // 관리자 런타임 설정 조회

    // benchmarksProvided=false면 요청에서 benchmarks가 생략된 것으로 보고 기존 값을 유지한다.
    RuntimeSettings updateSettings(UUID adminId, RuntimeSettings settings, boolean benchmarksProvided); // 전체 설정 원자적 갱신
}
