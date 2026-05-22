package com.kista.adapter.in.web;

import com.kista.domain.port.in.RegisterFcmTokenUseCase;
import com.kista.domain.port.in.UnregisterFcmTokenUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "FCM", description = "FCM 디바이스 토큰 관리")
@RestController
@RequestMapping("/api/fcm")
@RequiredArgsConstructor
public class FcmController {

    private final RegisterFcmTokenUseCase registerFcmToken;
    private final UnregisterFcmTokenUseCase unregisterFcmToken;

    // FCM 디바이스 토큰 등록 (WEB | ANDROID | IOS)
    @Operation(summary = "FCM 토큰 등록", description = "body: {\"token\": \"...\", \"platform\": \"WEB\"}")
    @ApiResponse(responseCode = "204", description = "등록 성공")
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerToken(@AuthenticationPrincipal UUID userId,
                              @RequestBody Map<String, String> body) {
        registerFcmToken.register(userId, body.get("token"), body.get("platform"));
    }

    // FCM 디바이스 토큰 삭제
    @Operation(summary = "FCM 토큰 삭제", description = "로그아웃 또는 알림 비활성화 시 호출")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @DeleteMapping("/tokens/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterToken(@AuthenticationPrincipal UUID userId,
                                @PathVariable String token) {
        unregisterFcmToken.unregister(userId, token);
    }
}
