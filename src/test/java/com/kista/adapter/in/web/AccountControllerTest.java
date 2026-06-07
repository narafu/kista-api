package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.in.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean RegisterAccountUseCase registerAccount;
    @MockitoBean UpdateAccountUseCase updateAccount;
    @MockitoBean DeleteAccountUseCase deleteAccount;
    @MockitoBean GetAccountUseCase getAccount;
    @MockitoBean KisConnectionTestUseCase connectionTest;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    // JwtAuthFilter와 동일하게 principal을 UUID로 설정
    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void list_accounts_returns_200() throws Exception {
        when(getAccount.listByUser(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/accounts")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_accounts_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testConnection_success_returns204() throws Exception {
        // void 메서드 — 기본 doNothing() stub, 성공 시 204 반환
        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void testConnection_failure_returns422() throws Exception {
        doThrow(new Account.InvalidKisKeyException()).when(connectionTest).test(anyString(), anyString(), any());

        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"wrongkey\",\"appSecret\":\"wrongsecret\"}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void testConnection_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
