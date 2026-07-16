package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.RuntimeSettingsResponse;
import com.kista.domain.port.in.RuntimeSettingsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "런타임 설정", description = "로그인 전 가입·계좌 등록·전략 생성 정책 공개 조회")
@RestController
@RequiredArgsConstructor
public class RuntimeConfigController {

    private final RuntimeSettingsUseCase runtimeSettingsUseCase; // 공개 설정 조회 유스케이스

    @Operation(summary = "공개 런타임 설정 조회", description = "가입 승인 필요 여부·증권사별 활성화·전략 생성 정책을 반환합니다. 로그인 없이 호출 가능하며 동적 설정이므로 캐시하지 않습니다.")
    @SecurityRequirements
    @GetMapping("/api/runtime-config")
    public ResponseEntity<RuntimeSettingsResponse> getRuntimeConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RuntimeSettingsResponse.from(runtimeSettingsUseCase.getSettings()));
    }
}
