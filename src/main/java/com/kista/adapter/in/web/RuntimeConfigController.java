package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.RuntimeSettingsResponse;
import com.kista.domain.port.in.RuntimeSettingsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeConfigController {

    private final RuntimeSettingsUseCase runtimeSettingsUseCase; // 공개 설정 조회 유스케이스

    @GetMapping("/api/runtime-config")
    public ResponseEntity<RuntimeSettingsResponse> getRuntimeConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RuntimeSettingsResponse.from(runtimeSettingsUseCase.getSettings()));
    }
}
