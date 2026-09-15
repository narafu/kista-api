package com.kista.account.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.trading.TradingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountInternalController.class)
@ContextConfiguration(classes = TradingApplication.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class AccountInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AccountPort accountPort;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void 계좌_목록을_from_to_없이_조회하면_전체를_반환한다() throws Exception {
        UUID accountId = UUID.randomUUID();
        given(accountPort.findAll()).willReturn(List.of(mockAccount(accountId)));

        mockMvc.perform(get("/api/internal/accounts")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(accountId.toString()));
    }

    @Test
    void 계좌_단건_조회_성공() throws Exception {
        // findByIdOrThrow는 AccountPort의 default 메서드 — Mockito mock은 default 메서드도
        // override하므로 findById가 아닌 findByIdOrThrow 자체를 직접 stub해야 한다 (testing.md 참고)
        UUID accountId = UUID.randomUUID();
        given(accountPort.findByIdOrThrow(accountId)).willReturn(mockAccount(accountId));

        mockMvc.perform(get("/api/internal/accounts/{id}", accountId)
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                // appKey/secretKey(복호화된 브로커 자격증명)가 내부망 응답에 실리지 않는지 확인
                .andExpect(jsonPath("$.appKey").doesNotExist())
                .andExpect(jsonPath("$.secretKey").doesNotExist())
                .andExpect(jsonPath("$.nickname").doesNotExist());
    }

    @Test
    void 계좌_단건_조회_없으면_404() throws Exception {
        UUID accountId = UUID.randomUUID();
        given(accountPort.findByIdOrThrow(accountId))
                .willThrow(new java.util.NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));

        mockMvc.perform(get("/api/internal/accounts/{id}", accountId)
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 전체_계좌_수를_조회한다() throws Exception {
        given(accountPort.countAll()).willReturn(7L);

        mockMvc.perform(get("/api/internal/accounts/count")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("7"));
    }

    private Account mockAccount(UUID id) {
        return new Account(id, UUID.randomUUID(), "테스트계좌", "74420614-01",
                "key", "secret", null, Broker.KIS, Instant.parse("2026-07-01T00:00:00Z"));
    }
}
