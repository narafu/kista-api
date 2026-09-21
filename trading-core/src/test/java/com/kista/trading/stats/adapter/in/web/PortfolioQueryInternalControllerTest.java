package com.kista.trading.stats.adapter.in.web;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.trading.domain.model.Order;
import com.kista.trading.stats.application.usecase.PortfolioUseCase;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PortfolioQueryInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class PortfolioQueryInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean PortfolioUseCase portfolioUseCase;

    private static final String VALID_TOKEN = "test-internal-token";
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void 현재_포트폴리오_현황을_조회한다() throws Exception {
        CyclePositionHistoryEntry entry = new CyclePositionHistoryEntry(
                UUID.randomUUID(), StrategyTicker.TQQQ,
                new BigDecimal("500.00"), new BigDecimal("105.00"), new BigDecimal("100.50"),
                10, Instant.now());
        given(portfolioUseCase.getCurrent(USER_ID)).willReturn(entry);

        mockMvc.perform(get("/api/internal/trading/stats/portfolio/current")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("TQQQ"))
                .andExpect(jsonPath("$.holdings").value(10))
                .andExpect(jsonPath("$.avgPrice").value(100.50));
    }

    @Test
    void 거래_내역을_조회한다() throws Exception {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 1, 5), StrategyTicker.TQQQ, OrderType.LOC, null, OrderDirection.BUY,
                "leg", 3, new BigDecimal("100.00"), null, null, null, null);
        given(portfolioUseCase.getHistory(USER_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), StrategyTicker.TQQQ))
                .willReturn(List.of(order));

        mockMvc.perform(get("/api/internal/trading/stats/portfolio/history")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString())
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("ticker", "TQQQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("TQQQ"))
                .andExpect(jsonPath("$[0].direction").value("BUY"))
                .andExpect(jsonPath("$[0].quantity").value(3));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/trading/stats/portfolio/current")
                        .param("userId", USER_ID.toString()))
                .andExpect(status().isUnauthorized());
    }
}
