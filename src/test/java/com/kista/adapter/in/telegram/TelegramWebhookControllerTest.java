package com.kista.adapter.in.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelegramWebhookController.class)
@WithMockUser
class TelegramWebhookControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder; // SupabaseJwtFilter 의존성 — 실제 JWKS 호출 방지
    @MockBean TelegramBotService botService;

    @Test
    void webhook_accepts_valid_payload_and_returns_200() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1,\"message\":{\"message_id\":1,\"chat\":{\"id\":12345},\"text\":\"/help\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void webhook_with_no_message_field_returns_200() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":2}"))
                .andExpect(status().isOk());
    }
}
