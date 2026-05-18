package com.kista.adapter.in.web;

import com.kista.domain.port.in.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockBean RegisterAccountUseCase registerAccount;
    @MockBean UpdateAccountUseCase updateAccount;
    @MockBean DeleteAccountUseCase deleteAccount;
    @MockBean GetAccountUseCase getAccount;
    @MockBean PauseStrategyUseCase pauseStrategy;
    @MockBean ResumeStrategyUseCase resumeStrategy;
    @MockBean KisConnectionTestUseCase connectionTest; // T3: 연결 테스트 UseCase

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    // JwtAuthFilter와 동일하게 principal을 UUID로 설정
    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void pause_returns_204_on_success() throws Exception {
        doNothing().when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void resume_returns_204_on_success() throws Exception {
        doNothing().when(resumeStrategy).resume(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/resume")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void pause_returns_403_when_not_owner() throws Exception {
        doThrow(new SecurityException("접근 불가")).when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void resume_returns_403_when_not_owner() throws Exception {
        doThrow(new SecurityException("접근 불가")).when(resumeStrategy).resume(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/resume")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void testConnection_success_returns200() throws Exception {
        when(connectionTest.test(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/api/accounts/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void testConnection_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/accounts/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pause_returns_404_when_account_not_found() throws Exception {
        doThrow(new NoSuchElementException("없음")).when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }
}
