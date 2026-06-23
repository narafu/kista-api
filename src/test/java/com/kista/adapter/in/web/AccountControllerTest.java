package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.port.in.AccountUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
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
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AccountUseCase accountUseCase;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    // JwtAuthFilter와 동일하게 principal을 UUID로 설정
    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void list_accounts_returns_200() throws Exception {
        when(accountUseCase.listByUser(any())).thenReturn(List.of());

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
        doThrow(new Account.InvalidKisKeyException()).when(accountUseCase).test(anyString(), anyString(), any());

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

    @Test
    void register_tossAccount_skipsAccountNoTest_returns201() throws Exception {
        // Toss 계좌 등록: AccountService.register()가 accountSeq 조회까지 통합 처리
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenReturn(new Account(UUID.fromString(USER_ID), UUID.fromString(USER_ID),
                        "토스계좌", "131-01-001931", "cid", "csecret", "42", Account.Broker.TOSS));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"토스계좌\",\"accountNo\":\"131-01-001931\"," +
                                "\"appKey\":\"cid\",\"secretKey\":\"csecret\",\"broker\":\"TOSS\"}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broker").value("TOSS"));

        // Toss 계좌는 testAccountNo() 미호출
        verify(accountUseCase, never()).testAccountNo(anyString(), anyString(), anyString());
    }

    @Test
    void register_kisAccount_callsAccountNoTest_returns201() throws Exception {
        // KIS 계좌 등록: testAccountNo()에 전체 accountNo 전달, 내부에서 CANO 분리
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenReturn(new Account(UUID.fromString(USER_ID), UUID.fromString(USER_ID),
                        "KIS계좌", "74420614", "appKey", "appSecret", "01", Account.Broker.KIS));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"KIS계좌\",\"accountNo\":\"74420614-01\"," +
                                "\"appKey\":\"appKey\",\"secretKey\":\"appSecret\"}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broker").value("KIS"));

        // testAccountNo에 전체 계좌번호 전달 — 내부에서 split('-')으로 CANO 분리
        verify(accountUseCase).testAccountNo("appKey", "appSecret", "74420614-01");
    }

    @Test
    void register_accountNo_invalidFormat_returns400() throws Exception {
        // 잘못된 형식(9자리 등) → @Pattern 검증 실패 → 400
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"계좌\",\"accountNo\":\"123456789\"," +
                                "\"appKey\":\"key\",\"secretKey\":\"secret\"}")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isBadRequest());
    }
}
