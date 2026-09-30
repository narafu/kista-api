package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.RuntimeSettingsBundle;

public interface RuntimeSettingsUseCase {
    RuntimeSettingsBundle getSettings(); // 공개 런타임 설정 조회 — root 설정 + trading-core 정책 묶음
}
