package com.kista.adapter.in.telegram;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // TelegramBotService가 package-private
public class TelegramWebhookController {

    private final TelegramBotService botService;

    @PostMapping("/telegram/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void handleWebhook(@RequestBody TelegramUpdate update) {
        botService.handle(update);
    }
}
