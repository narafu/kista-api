package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TelegramSettingsResponse;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.UpdateUserTelegramUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "설정", description = "텔레그램 봇 알림 설정 관리")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UpdateUserTelegramUseCase updateUserTelegram;
    private final GetUserUseCase getUser;

    // 텔레그램 봇 설정 조회 (chatId 반환, botToken은 보안상 미노출)
    @Operation(summary = "텔레그램 설정 조회", description = "현재 설정된 텔레그램 채팅 ID 반환. botToken은 보안상 응답에서 제외.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/telegram")
    public TelegramSettingsResponse getTelegram(@AuthenticationPrincipal UUID userId) {
        return TelegramSettingsResponse.from(getUser.getById(userId));
    }

    // 텔레그램 봇 설정 (botToken, chatId 저장)
    @Operation(summary = "텔레그램 설정 저장", description = "텔레그램 봇 토큰과 채팅 ID를 AES-256 암호화하여 저장. body: {\"botToken\": \"...\", \"chatId\": \"...\"}")
    @ApiResponse(responseCode = "204", description = "저장 성공")
    @PutMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTelegram(@AuthenticationPrincipal UUID userId,
                               @RequestBody Map<String, String> body) {
        String botToken = body.get("botToken");
        String chatId = body.get("chatId");
        updateUserTelegram.updateTelegram(userId, botToken, chatId);
    }

    // 텔레그램 봇 설정 해제
    @Operation(summary = "텔레그램 설정 해제", description = "저장된 텔레그램 봇 토큰과 채팅 ID를 삭제.")
    @ApiResponse(responseCode = "204", description = "해제 성공")
    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTelegram(@AuthenticationPrincipal UUID userId) {
        updateUserTelegram.removeTelegram(userId);
    }
}
