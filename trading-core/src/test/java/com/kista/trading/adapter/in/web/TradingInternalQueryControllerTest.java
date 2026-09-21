package com.kista.trading.adapter.in.web;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.application.port.output.OrderPort;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradingInternalQueryController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class TradingInternalQueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean OrderPort orderPort;
    @MockitoBean StrategyPort strategyPort;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void listOrders_인증없이_요청하면_401() throws Exception {
        mockMvc.perform(get("/api/internal/trading/orders")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listOrders_인증되면_200() throws Exception {
        given(orderPort.findAll(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))).willReturn(List.of());

        mockMvc.perform(get("/api/internal/trading/orders")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isOk());
    }

    // 소유권 검증 실패 — TradingQueryHttpAdapter가 404를 NoSuchElementException으로
    // 되돌리는 계약의 상대편. 여기서 404가 실제로 나오는지 확인한다.
    @Test
    void listStrategyOrders_다른_계좌의_전략이면_404() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        given(strategyPort.findByIdOrThrow(strategyId)).willReturn(strategy);

        mockMvc.perform(get("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders",
                        otherAccountId, strategyId)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("tradeDate", "2026-07-01"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listStrategyOrders_계좌ID가_일치하면_200() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        LocalDate tradeDate = LocalDate.of(2026, 7, 1);
        Strategy strategy = new Strategy(strategyId, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        given(strategyPort.findByIdOrThrow(strategyId)).willReturn(strategy);
        given(orderPort.findByStrategyId(strategyId, tradeDate, tradeDate)).willReturn(List.of());

        mockMvc.perform(get("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders",
                        accountId, strategyId)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("tradeDate", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listStrategyTradeDates_다른_계좌의_전략이면_404() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        given(strategyPort.findByIdOrThrow(strategyId)).willReturn(strategy);

        mockMvc.perform(get("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates",
                        otherAccountId, strategyId)
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }
}
