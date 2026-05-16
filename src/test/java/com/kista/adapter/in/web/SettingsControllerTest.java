package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.UpdateUserTelegramUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettingsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class SettingsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockBean UpdateUserTelegramUseCase updateUserTelegram;
    @MockBean GetUserUseCase getUser;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void put_telegram_returns_204_and_calls_usecase() throws Exception {
        Map<String, String> body = Map.of("botToken", "test-token", "chatId", "chat-123");

        mockMvc.perform(put("/api/settings/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());

        verify(updateUserTelegram).updateTelegram(
                eq(UUID.fromString(USER_ID)), eq("test-token"), eq("chat-123"));
    }

    @Test
    void delete_telegram_returns_204_and_calls_usecase() throws Exception {
        mockMvc.perform(delete("/api/settings/telegram")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());

        verify(updateUserTelegram).removeTelegram(eq(UUID.fromString(USER_ID)));
    }

    @Test
    void put_telegram_without_auth_returns_401() throws Exception {
        Map<String, String> body = Map.of("botToken", "test-token", "chatId", "chat-123");

        mockMvc.perform(put("/api/settings/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
