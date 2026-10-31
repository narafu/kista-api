package com.kista.tradingcore.account.adapter.in.web;

import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.domain.model.RegisterAccountCommand;
import com.kista.tradingcore.account.application.usecase.AccountUseCase;
import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AccountUseCase accountUseCase;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void list_accounts_returns_200() throws Exception {
        when(accountUseCase.listByUser(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/accounts")
                        .with(authentication(userToken(UUID.fromString(USER_ID)))))
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
                        .content("{\"broker\":\"KIS\",\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isNoContent());
    }

    @Test
    void testConnection_failure_returns422() throws Exception {
        doThrow(new BrokerCredentialException()).when(accountUseCase).test(any(), anyString(), anyString());

        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"wrongkey\",\"appSecret\":\"wrongsecret\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void testConnection_disabledBroker_returns400() throws Exception {
        // 서비스의 증권사 비활성 검증 예외는 사용자 입력 오류로 노출한다.
        doThrow(new IllegalArgumentException("한국투자증권 신규 계좌 등록이 비활성화되어 있습니다."))
                .when(accountUseCase).test(any(), anyString(), anyString());

        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broker\":\"KIS\",\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testConnection_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broker\":\"KIS\",\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_tossAccount_returns201() throws Exception {
        // Toss 계좌 등록: AccountService.register()가 accountSeq 조회까지 통합 처리
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenReturn(new Account(UUID.fromString(USER_ID), UUID.fromString(USER_ID),
                        "토스계좌", "131-01-001931", "cid", "csecret", "42", Broker.TOSS, null, null));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"토스계좌\",\"accountNo\":\"131-01-001931\"," +
                                "\"appKey\":\"cid\",\"secretKey\":\"csecret\",\"broker\":\"TOSS\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broker").value("TOSS"));
    }

    @Test
    void register_kisAccount_returns201() throws Exception {
        // KIS 계좌 등록: AccountService.register()가 verifyAccount 통합 처리
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenReturn(new Account(UUID.fromString(USER_ID), UUID.fromString(USER_ID),
                        "KIS계좌", "74420614", "appKey", "appSecret", null, Broker.KIS, null, null));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"KIS계좌\",\"accountNo\":\"74420614-01\"," +
                                "\"appKey\":\"appKey\",\"secretKey\":\"appSecret\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broker").value("KIS"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void register_upbit_responseHasNullAccountNoAndKrwCurrency() throws Exception {
        java.time.Instant expiresAt = java.time.Instant.parse("2027-10-17T00:00:00Z");
        when(accountUseCase.register(any(), any())).thenReturn(new Account(UUID.randomUUID(), UUID.fromString(USER_ID),
                "업비트", null, "ak", "sk", null, Broker.UPBIT, null, expiresAt));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"업비트\",\"appKey\":\"ak\",\"secretKey\":\"sk\",\"broker\":\"UPBIT\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broker").value("UPBIT"))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.accountNo").doesNotExist())
                .andExpect(jsonPath("$.accountNoMasked").doesNotExist())
                .andExpect(jsonPath("$.apiKeyExpiresAt").value("2027-10-17T00:00:00Z"));
    }

    @Test
    void testConnection_upbit_passesBrokerToUseCase() throws Exception {
        mockMvc.perform(post("/api/accounts/connection-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broker\":\"UPBIT\",\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isNoContent());
        verify(accountUseCase).test(eq(Broker.UPBIT), eq("testkey1234"), eq("testsecret1234"));
    }

    @Test
    void register_duplicate_returns409WithCodeInBody() throws Exception {
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenThrow(new Account.DuplicateAccountException("74420614-01"));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"KIS계좌\",\"accountNo\":\"74420614-01\"," +
                                "\"appKey\":\"appKey\",\"secretKey\":\"appSecret\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"))
                .andExpect(jsonPath("$.detail").value("이미 등록된 계좌입니다."));
    }

    @Test
    void register_disabledBroker_returns400() throws Exception {
        // 신규 계좌 등록도 연결 테스트와 동일한 사용자 입력 오류 응답을 사용한다.
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenThrow(new IllegalArgumentException("토스증권 신규 계좌 등록이 비활성화되어 있습니다."));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"토스계좌\",\"accountNo\":\"131-01-001931\"," +
                                "\"appKey\":\"cid\",\"secretKey\":\"csecret\",\"broker\":\"TOSS\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_notOwned_returns403() throws Exception {
        // SecurityException은 tradingcore.web.TradingExceptionHandler의 6종 전용 매핑 대상이 아니지만,
        // ProblemDetailMappings.GENERIC(handleGeneric)이 root GlobalExceptionHandler와 동일하게 403으로 매핑한다 —
        // testConnection_failure_returns422(위, 6종 전용 매핑)와 짝을 이뤄 TradingExceptionHandler
        // 내부의 두 매핑 경로(전용 6종 vs 범용 GENERIC)를 모두 실측한다.
        UUID accountId = UUID.randomUUID();
        doThrow(new SecurityException("계좌에 대한 접근 권한이 없습니다"))
                .when(accountUseCase).delete(eq(accountId), any());

        mockMvc.perform(delete("/api/accounts/" + accountId)
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void register_accountNo_invalidFormat_returns400() throws Exception {
        // 잘못된 형식(9자리 등) → @Pattern 검증 실패 → 400
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"계좌\",\"accountNo\":\"123456789\"," +
                                "\"appKey\":\"key\",\"secretKey\":\"secret\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCredentials_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(accountUseCase.updateCredentials(eq(id), eq(UUID.fromString(USER_ID)), any()))
                .thenReturn(new Account(id, UUID.fromString(USER_ID), "업비트", null, "new-ak", "new-sk", null, Broker.UPBIT, null,
                        java.time.Instant.parse("2027-10-17T00:00:00Z")));

        mockMvc.perform(put("/api/accounts/{id}/credentials", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"new-ak\",\"secretKey\":\"new-sk\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyExpiresAt").value("2027-10-17T00:00:00Z"))
                .andExpect(jsonPath("$.appKey").doesNotExist());
        verify(accountUseCase).updateCredentials(id, UUID.fromString(USER_ID),
                new com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand("new-ak", "new-sk"));
    }

    @Test
    void updateCredentials_blankKey_returns400() throws Exception {
        mockMvc.perform(put("/api/accounts/{id}/credentials", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\" \",\"secretKey\":\"sk\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isBadRequest());
    }
}
