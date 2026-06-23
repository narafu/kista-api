package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettingsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class SettingsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean UserUseCase userUseCase;
    @MockitoBean UpdateBalanceCheckUseCase updateBalanceCheckUseCase;
    @MockitoBean UpdateNotificationPrefUseCase updateNotificationPrefUseCase;

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

        verify(userUseCase).updateTelegram(
                eq(UUID.fromString(USER_ID)), eq("test-token"), eq("chat-123"));
    }

    @Test
    void delete_telegram_returns_204_and_calls_usecase() throws Exception {
        mockMvc.perform(delete("/api/settings/telegram")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());

        verify(userUseCase).removeTelegram(eq(UUID.fromString(USER_ID)));
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

    @Test
    void updateNotificationChannel_fcm_returns204() throws Exception {
        mockMvc.perform(patch("/api/settings/notification-channel")
                        .with(csrf())
                        .with(authentication(mockAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"FCM\"}"))
                .andExpect(status().isNoContent());
        verify(userUseCase).updateNotificationChannel(any(), eq(NotificationChannel.FCM));
    }

    @Test
    void updateNotificationChannel_invalidValue_returns400() throws Exception {
        mockMvc.perform(patch("/api/settings/notification-channel")
                        .with(csrf())
                        .with(authentication(mockAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchNickname_valid_returns204() throws Exception {
        mockMvc.perform(patch("/api/settings/nickname")
                        .with(csrf()).with(authentication(mockAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isNoContent());
        verify(userUseCase).updateNickname(eq(UUID.fromString(USER_ID)), eq("새닉네임"));
    }

    @Test
    void patchNickname_blank_returns400() throws Exception {
        mockMvc.perform(patch("/api/settings/nickname")
                        .with(csrf()).with(authentication(mockAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_notifications_trading_alert_returns_204() throws Exception {
        mockMvc.perform(patch("/api/settings/notifications/TRADING_ALERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());

        verify(updateNotificationPrefUseCase).update(
                argThat(cmd -> cmd.type().name().equals("TRADING_ALERT") && !cmd.enabled()));
    }

    @Test
    void patch_notifications_unknown_type_returns_400() throws Exception {
        mockMvc.perform(patch("/api/settings/notifications/UNKNOWN_TYPE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_balance_check_calls_use_case() throws Exception {
        mockMvc.perform(patch("/api/settings/balance-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());

        verify(updateBalanceCheckUseCase).update(argThat(cmd -> !cmd.enabled()));
    }
}
