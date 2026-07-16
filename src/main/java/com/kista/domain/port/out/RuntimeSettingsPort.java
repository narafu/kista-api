package com.kista.domain.port.out;

import com.kista.domain.model.settings.RuntimeSettings;

public interface RuntimeSettingsPort {
    RuntimeSettings load(); // 현재 설정 조회

    RuntimeSettings loadForUpdate(); // 승인 상태 결정과 설정 변경을 직렬화하는 잠금 조회

    RuntimeSettings save(RuntimeSettings settings); // 전체 설정 저장
}
