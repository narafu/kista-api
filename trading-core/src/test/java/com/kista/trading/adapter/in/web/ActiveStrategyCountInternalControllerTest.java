package com.kista.trading.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Strategy;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActiveStrategyCountInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class ActiveStrategyCountInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AccountPort accountPort;
    @MockitoBean StrategyPort strategyPort;

    private static final String VALID_TOKEN = "test-internal-token";

    // 사용자 전 계좌의 ACTIVE 전략만 합산 — 구 ActiveStrategyCountAdapterTest 시나리오 이관
    @Test
    void 사용자_전_계좌의_ACTIVE_전략만_합산한다() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId1 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        Account account1 = mockAccount(accountId1);
        Account account2 = mockAccount(accountId2);
        given(accountPort.findByUserId(userId)).willReturn(List.of(account1, account2));
        given(strategyPort.findByAccountId(accountId1)).willReturn(List.of(
                strategy(accountId1, StrategyStatus.ACTIVE), strategy(accountId1, StrategyStatus.PAUSED)));
        given(strategyPort.findByAccountId(accountId2)).willReturn(List.of(
                strategy(accountId2, StrategyStatus.ACTIVE)));

        mockMvc.perform(get("/api/internal/trading/active-strategy-count")
                        .param("userId", userId.toString())
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/trading/active-strategy-count")
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    private Account mockAccount(UUID id) {
        Account account = mock(Account.class);
        given(account.id()).willReturn(id);
        return account;
    }

    private Strategy strategy(UUID accountId, StrategyStatus status) {
        return new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, status,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }
}
