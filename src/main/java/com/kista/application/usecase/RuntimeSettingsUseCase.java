package com.kista.application.usecase;

import com.kista.domain.model.settings.RuntimeSettings;

public interface RuntimeSettingsUseCase {
    RuntimeSettings getSettings(); // 공개 런타임 설정 조회
}
