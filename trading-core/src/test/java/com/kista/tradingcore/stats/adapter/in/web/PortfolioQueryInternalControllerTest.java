package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.stats.application.usecase.PortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.PortfolioOrder;
import com.kista.tradingcore.stats.domain.model.PortfolioPosition;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.platform.security.TokenBlacklistPort;
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
    void 현재_포트폴리오_현황을_전략별_목록으로_조회한다() throws Exception {
        CyclePositionHistoryEntry entry = new CyclePositionHistoryEntry(
                UUID.randomUUID(), UUID.randomUUID(), StrategyTicker.ETH, StrategyType.DCA,
                new BigDecimal("500.00"), new BigDecimal("3000.00"), new BigDecimal("2900.50"),
                new BigDecimal("0.5"), Instant.now());
        given(portfolioUseCase.getCurrent(USER_ID)).willReturn(List.of(new PortfolioPosition(entry, Currency.USDT)));

        mockMvc.perform(get("/api/internal/trading/stats/portfolio/current")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].strategyType").value("DCA"))
                .andExpect(jsonPath("$[0].ticker").value("ETH"))
                .andExpect(jsonPath("$[0].currency").value("USDT"))
                .andExpect(jsonPath("$[0].holdings").value(0.5))
                .andExpect(jsonPath("$[0].avgPrice").value(2900.50));
    }

    @Test
    void 포트폴리오가_없으면_빈_목록_200을_반환한다() throws Exception {
        given(portfolioUseCase.getCurrent(USER_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/internal/trading/stats/portfolio/current")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void 거래_내역을_ticker_파라미터_없이_전_종목으로_조회한다() throws Exception {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 1, 5), StrategyTicker.TQQQ, OrderType.LOC, null, OrderDirection.BUY,
                "leg", BigDecimal.valueOf(3), new BigDecimal("100.00"), null, null, null, null);
        given(portfolioUseCase.getHistory(USER_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .willReturn(List.of(new PortfolioOrder(order, Currency.USD)));

        mockMvc.perform(get("/api/internal/trading/stats/portfolio/history")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString())
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("TQQQ"))
                .andExpect(jsonPath("$[0].direction").value("BUY"))
                .andExpect(jsonPath("$[0].quantity").value(3))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/trading/stats/portfolio/current")
                        .param("userId", USER_ID.toString()))
                .andExpect(status().isUnauthorized());
    }
}
