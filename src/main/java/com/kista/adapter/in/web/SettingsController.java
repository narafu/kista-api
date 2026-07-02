package com.kista.adapter.in.web;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase.UpdateBalanceCheckCommand;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase.UpdateNotificationPrefCommand;
import com.kista.domain.port.in.UserProfileUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    private final UserProfileUseCase userProfileUseCase;
    private final UpdateBalanceCheckUseCase updateBalanceCheckUseCase;       // 잔고검증 설정 — user_settings 테이블
    private final UpdateNotificationPrefUseCase updateNotificationPrefUseCase; // 알림 타입별 on/off — user_notification_prefs 테이블

    record TelegramUpdateRequest(@NotBlank String botToken, @NotBlank String chatId) {} // 텔레그램 설정 요청 body
    record NotificationChannelRequest(@NotBlank String channel) {}                      // 알림 채널 변경 요청 body
    record BalanceCheckRequest(boolean enabled) {}                                      // 잔고 검증 설정 요청 body
    record NotificationPrefRequest(boolean enabled) {}                                  // 알림 타입별 on/off 요청 body
    record NicknameRequest(                                                             // 닉네임 변경 요청 body
        @NotBlank
        @Size(max = 10, message = "닉네임은 10자 이내여야 합니다")
        @Pattern(regexp = "^[\\p{L}\\d ]{1,10}$", message = "한글·영문·숫자·공백 1~10자")
        String nickname
    ) {}

    // 텔레그램 봇 설정 (botToken, chatId 저장 + getMe로 username 검증) — IllegalArgumentException→400 GlobalExceptionHandler 처리
    @Operation(summary = "텔레그램 설정 저장", description = "텔레그램 봇 토큰과 채팅 ID를 AES-256 암호화하여 저장. body: {\"botToken\": \"...\", \"chatId\": \"...\"}")
    @ApiResponse(responseCode = "204", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "유효하지 않은 Bot Token")
    @PutMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTelegram(@AuthenticationPrincipal UUID userId,
                               @Valid @RequestBody TelegramUpdateRequest body) {
        userProfileUseCase.updateTelegram(userId, body.botToken(), body.chatId());
    }

    // 텔레그램 봇 설정 해제
    @Operation(summary = "텔레그램 설정 해제", description = "저장된 텔레그램 봇 토큰과 채팅 ID를 삭제.")
    @ApiResponse(responseCode = "204", description = "해제 성공")
    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTelegram(@AuthenticationPrincipal UUID userId) {
        userProfileUseCase.removeTelegram(userId);
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
        userProfileUseCase.updateNotificationChannel(userId, channel);
    }

    // 잔고 검증 설정 변경 (false=예수금 부족해도 전략 생성·재등록 허용)
    @Operation(summary = "잔고 검증 설정", description = "false 시 실잔고 미확인 모드 — 예수금 부족해도 전략 등록·재등록 가능. body: {\"enabled\": false}")
    @ApiResponse(responseCode = "204", description = "변경 성공")
    @PatchMapping("/balance-check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBalanceCheck(@AuthenticationPrincipal UUID userId,
                                   @RequestBody BalanceCheckRequest body) {
        updateBalanceCheckUseCase.update(new UpdateBalanceCheckCommand(userId, body.enabled()));
    }

    // 알림 타입 on/off (TRADING_ALERT 등)
    @Operation(summary = "알림 타입 on/off", description = "TRADING_ALERT 등 알림 타입별 활성화 여부. body: {\"enabled\": false}")
    @ApiResponse(responseCode = "204", description = "변경 성공")
    @ApiResponse(responseCode = "400", description = "알 수 없는 알림 타입")
    @PatchMapping("/notifications/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateNotificationPref(@AuthenticationPrincipal UUID userId,
                                       @PathVariable String type,
                                       @RequestBody NotificationPrefRequest body) {
        // type → NotificationType 변환 (불일치 시 IllegalArgumentException → 400)
        NotificationType notificationType;
        try {
            notificationType = NotificationType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 알림 타입: " + type + ". 허용값: TRADING_ALERT");
        }
        updateNotificationPrefUseCase.update(new UpdateNotificationPrefCommand(userId, notificationType, body.enabled()));
    }

    // 닉네임 변경 (1~10자, 한글·영문·숫자·공백)
    @Operation(summary = "닉네임 변경", description = "KISTA 닉네임을 변경합니다. 한글·영문·숫자·공백 1~10자. body: {\"nickname\": \"새닉네임\"}")
    @ApiResponse(responseCode = "204", description = "변경 성공")
    @ApiResponse(responseCode = "400", description = "유효하지 않은 닉네임")
    @PatchMapping("/nickname")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateNickname(@AuthenticationPrincipal UUID userId,
                               @Valid @RequestBody NicknameRequest body) {
        userProfileUseCase.updateNickname(userId, body.nickname());
    }
}
