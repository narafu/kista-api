package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TelegramSettingsResponse;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.UserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "설정", description = "텔레그램 봇 알림 설정 관리")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UserUseCase userUseCase;

    record TelegramUpdateRequest(@NotBlank String botToken, @NotBlank String chatId) {} // 텔레그램 설정 요청 body
    record NotificationChannelRequest(@NotBlank String channel) {}                      // 알림 채널 변경 요청 body
    record BalanceCheckRequest(boolean enabled) {}                                      // 잔고 검증 설정 요청 body

    // 텔레그램 봇 설정 조회 (chatId 반환, botToken은 보안상 미노출)
    @Operation(summary = "텔레그램 설정 조회", description = "현재 설정된 텔레그램 채팅 ID 반환. botToken은 보안상 응답에서 제외.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/telegram")
    public TelegramSettingsResponse getTelegram(@AuthenticationPrincipal UUID userId) {
        return TelegramSettingsResponse.from(userUseCase.getById(userId));
    }

    // 텔레그램 봇 설정 (botToken, chatId 저장 + getMe로 username 검증) — IllegalArgumentException→400 GlobalExceptionHandler 처리
    @Operation(summary = "텔레그램 설정 저장", description = "텔레그램 봇 토큰과 채팅 ID를 AES-256 암호화하여 저장. body: {\"botToken\": \"...\", \"chatId\": \"...\"}")
    @ApiResponse(responseCode = "204", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "유효하지 않은 Bot Token")
    @PutMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTelegram(@AuthenticationPrincipal UUID userId,
                               @Valid @RequestBody TelegramUpdateRequest body) {
        userUseCase.updateTelegram(userId, body.botToken(), body.chatId());
    }

    // 텔레그램 봇 설정 해제
    @Operation(summary = "텔레그램 설정 해제", description = "저장된 텔레그램 봇 토큰과 채팅 ID를 삭제.")
    @ApiResponse(responseCode = "204", description = "해제 성공")
    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTelegram(@AuthenticationPrincipal UUID userId) {
        userUseCase.removeTelegram(userId);
    }

    // 알림 채널 변경 (TELEGRAM / FCM / ALL 중 선택) — IllegalArgumentException→400 GlobalExceptionHandler 처리
    @Operation(summary = "알림 채널 변경", description = "TELEGRAM / FCM / ALL 중 선택. body: {\"channel\": \"FCM\"}")
    @ApiResponse(responseCode = "204", description = "변경 성공")
    @PatchMapping("/notification-channel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateNotificationChannel(@AuthenticationPrincipal UUID userId,
                                           @Valid @RequestBody NotificationChannelRequest body) {
        NotificationChannel channel = NotificationChannel.tryParse(body.channel())
                .orElseThrow(() -> new IllegalArgumentException(
                        "알 수 없는 알림 채널: " + body.channel() + ". 허용값: NONE, TELEGRAM, FCM, ALL"));
        userUseCase.updateNotificationChannel(userId, channel);
    }

    // 잔고 검증 설정 변경 (false=예수금 부족해도 전략 생성·재등록 허용)
    @Operation(summary = "잔고 검증 설정", description = "false 시 실잔고 미확인 모드 — 예수금 부족해도 전략 등록·재등록 가능. body: {\"enabled\": false}")
    @ApiResponse(responseCode = "204", description = "변경 성공")
    @PatchMapping("/balance-check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBalanceCheck(@AuthenticationPrincipal UUID userId,
                                   @RequestBody BalanceCheckRequest body) {
        userUseCase.updateBalanceCheckEnabled(userId, body.enabled());
    }
}
