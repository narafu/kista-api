package com.kista.adapter.in.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "텔레그램", description = "텔레그램 봇 웹훅 수신 엔드포인트")
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // TelegramBotService가 package-private
public class TelegramWebhookController {

    private final TelegramBotService botService;

    @Operation(summary = "텔레그램 웹훅 수신", description = "텔레그램 서버에서 전송하는 Update(버튼 클릭, 메시지 등)를 수신하여 처리.")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @PostMapping("/telegram/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void handleWebhook(@RequestBody TelegramUpdate update) {
        botService.handle(update);
    }
}
