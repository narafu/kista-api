package com.kista.adapter.in.telegram;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class TelegramWebhookController {

    private final TelegramBotService botService;

    public TelegramWebhookController(TelegramBotService botService) {
        this.botService = botService;
    }

    @PostMapping("/telegram/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void handleWebhook(@RequestBody TelegramUpdate update) {
        botService.handle(update);
    }
}
