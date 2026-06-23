package com.kista.adapter.in.web;

import com.kista.domain.port.in.BlacklistUseCase;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FcmController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FcmControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean UserUseCase userUseCase;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void registerToken_returns204() throws Exception {
        mockMvc.perform(post("/api/fcm/tokens")
                        .with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-abc\",\"platform\":\"WEB\"}"))
                .andExpect(status().isNoContent());
        verify(userUseCase).registerFcmToken(eq(USER_ID), eq("fcm-token-abc"), eq("WEB"));
    }

    @Test
    void unregisterToken_returns204() throws Exception {
        mockMvc.perform(delete("/api/fcm/tokens/fcm-token-abc")
                        .with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()))))
                .andExpect(status().isNoContent());
        verify(userUseCase).unregisterFcmToken(eq(USER_ID), eq("fcm-token-abc"));
    }
}
