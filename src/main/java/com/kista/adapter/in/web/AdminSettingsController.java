package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminSettingsRequest;
import com.kista.adapter.in.web.dto.RuntimeSettingsResponse;
import com.kista.domain.port.in.AdminSettingsUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final AdminSettingsUseCase adminSettingsUseCase; // 관리자 설정 조회·갱신 유스케이스

    @GetMapping
    public ResponseEntity<RuntimeSettingsResponse> getSettings() {
        return noStore(RuntimeSettingsResponse.from(adminSettingsUseCase.getSettings()));
    }

    @PutMapping
    public ResponseEntity<RuntimeSettingsResponse> updateSettings(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid AdminSettingsRequest request) {
        // 요청 전체를 도메인 설정으로 검증한 뒤 단일 갱신 유스케이스를 호출한다.
        return noStore(RuntimeSettingsResponse.from(adminSettingsUseCase.updateSettings(adminId, request.toDomain())));
    }

    private ResponseEntity<RuntimeSettingsResponse> noStore(RuntimeSettingsResponse response) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
