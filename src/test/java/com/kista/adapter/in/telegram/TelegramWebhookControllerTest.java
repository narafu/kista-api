package com.kista.adapter.in.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelegramWebhookController.class)
class TelegramWebhookControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean TelegramBotService botService;

    @Test
    void webhook_accepts_valid_payload_and_returns_200() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1,\"message\":{\"message_id\":1,\"chat\":{\"id\":12345},\"text\":\"/help\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void webhook_with_no_message_field_returns_200() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":2}"))
                .andExpect(status().isOk());
    }
}
