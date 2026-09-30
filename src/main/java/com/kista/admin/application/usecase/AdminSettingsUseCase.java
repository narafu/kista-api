package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.RuntimeSettingsBundle;

import java.util.UUID;

public interface AdminSettingsUseCase {
    RuntimeSettingsBundle getSettings(); // 관리자 런타임 설정 조회 — root 설정 + trading-core 정책 묶음
    // benchmarksProvided=false면 요청에서 benchmarks가 생략된 것으로 보고 기존 값을 유지한다.
    // trading-core 정책 → root 설정 순으로 반영하며 프로세스가 갈려 있어 원자적이지 않다(AdminSettingsService 참고).
    RuntimeSettingsBundle updateSettings(UUID adminId, RuntimeSettingsBundle settings, boolean benchmarksProvided);
}
