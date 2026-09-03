package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.RuntimeSettings;

public interface RuntimeSettingsUseCase {
    RuntimeSettings getSettings(); // 공개 런타임 설정 조회
}
