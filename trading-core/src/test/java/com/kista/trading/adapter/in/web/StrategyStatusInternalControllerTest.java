package com.kista.trading.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.usecase.StrategyUseCase;
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

import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// admin의 AdminStrategyService.pause/resumeStrategy가 소비하는 내부 전용 상태 전이 엔드포인트 —
// 소유권 검증(strategy.accountId == accountId)이 여기로 이관됐다.
@WebMvcTest(StrategyStatusInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class StrategyStatusInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AccountPort accountPort;
    @MockitoBean StrategyPort strategyPort;
    @MockitoBean StrategyUseCase strategyUseCase;

    private static final String VALID_TOKEN = "test-internal-token";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    private static Account account(UUID id) {
        return new Account(id, UUID.randomUUID(), "계좌", "12345678", "app", "secret", null, Broker.KIS, null);
    }

    private static Strategy strategy(UUID id, UUID accountId) {
        return strategy(id, accountId, StrategyStatus.ACTIVE);
    }

    private static Strategy strategy(UUID id, UUID accountId, StrategyStatus status) {
        return new Strategy(id, accountId, StrategyType.PRIVACY, status,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    @Test
    void 소유권_일치하고_ACTIVE_전략이면_PAUSED_요청은_pause_위임() throws Exception {
        Account account = account(ACCOUNT_ID);
        given(accountPort.findByIdOrThrow(ACCOUNT_ID)).willReturn(account);
        given(strategyPort.findByIdOrThrow(STRATEGY_ID)).willReturn(strategy(STRATEGY_ID, ACCOUNT_ID));

        mockMvc.perform(patch("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status",
                        ACCOUNT_ID, STRATEGY_ID)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("status", "PAUSED"))
                .andExpect(status().isOk());

        verify(strategyUseCase).pause(STRATEGY_ID, account.userId());
    }

    @Test
    void PAUSED_전략이면_ACTIVE_요청은_resume_위임해_종료된_사이클을_재오픈한다() throws Exception {
        Account account = account(ACCOUNT_ID);
        given(accountPort.findByIdOrThrow(ACCOUNT_ID)).willReturn(account);
        given(strategyPort.findByIdOrThrow(STRATEGY_ID))
                .willReturn(strategy(STRATEGY_ID, ACCOUNT_ID, StrategyStatus.PAUSED));

        mockMvc.perform(patch("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status",
                        ACCOUNT_ID, STRATEGY_ID)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk());

        verify(strategyUseCase).resume(STRATEGY_ID, account.userId());
    }

    @Test
    void 이미_같은_상태면_아무것도_하지_않는다() throws Exception {
        given(accountPort.findByIdOrThrow(ACCOUNT_ID)).willReturn(account(ACCOUNT_ID));
        given(strategyPort.findByIdOrThrow(STRATEGY_ID)).willReturn(strategy(STRATEGY_ID, ACCOUNT_ID));

        mockMvc.perform(patch("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status",
                        ACCOUNT_ID, STRATEGY_ID)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk());

        verify(strategyUseCase, never()).resume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(strategyUseCase, never()).pause(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 계좌에_속하지_않는_전략이면_400() throws Exception {
        UUID otherAccountId = UUID.randomUUID();
        given(accountPort.findByIdOrThrow(ACCOUNT_ID)).willReturn(account(ACCOUNT_ID));
        given(strategyPort.findByIdOrThrow(STRATEGY_ID)).willReturn(strategy(STRATEGY_ID, otherAccountId));

        mockMvc.perform(patch("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status",
                        ACCOUNT_ID, STRATEGY_ID)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("status", "PAUSED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 계좌가_없으면_404() throws Exception {
        given(accountPort.findByIdOrThrow(ACCOUNT_ID))
                .willThrow(new java.util.NoSuchElementException("계좌를 찾을 수 없습니다: " + ACCOUNT_ID));

        mockMvc.perform(patch("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status",
                        ACCOUNT_ID, STRATEGY_ID)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("status", "PAUSED"))
                .andExpect(status().isNotFound());
    }
}
