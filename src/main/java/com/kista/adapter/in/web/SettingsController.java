package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TelegramSettingsResponse;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.UpdateUserTelegramUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UpdateUserTelegramUseCase updateUserTelegram;
    private final GetUserUseCase getUser;

    // 텔레그램 봇 설정 조회 (chatId 반환, botToken은 보안상 미노출)
    @GetMapping("/telegram")
    public TelegramSettingsResponse getTelegram(@AuthenticationPrincipal UUID userId) {
        return TelegramSettingsResponse.from(getUser.getById(userId));
    }

    // 텔레그램 봇 설정 (botToken, chatId 저장)
    @PutMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTelegram(@AuthenticationPrincipal UUID userId,
                               @RequestBody Map<String, String> body) {
        String botToken = body.get("botToken");
        String chatId = body.get("chatId");
        updateUserTelegram.updateTelegram(userId, botToken, chatId);
    }

    // 텔레그램 봇 설정 해제
    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTelegram(@AuthenticationPrincipal UUID userId) {
        updateUserTelegram.removeTelegram(userId);
    }
}
