package com.kista.trading.stats.adapter.in.web;

import com.kista.broker.application.port.output.ExchangeRatePort;
import com.kista.trading.stats.application.usecase.InvestmentPointsQuery;
import com.kista.trading.stats.domain.model.InvestmentPointsResult;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradingStatsInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class TradingStatsInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean InvestmentPointsQuery investmentPointsQuery;
    @MockitoBean ExchangeRatePort exchangeRatePort; // TradingStatsInternalController의 환율 조회 라우트 의존성 (Task15)

    private static final String VALID_TOKEN = "test-internal-token";
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void 투자_성과_시리즈를_조회한다() throws Exception {
        InvestmentPointsResult response = new InvestmentPointsResult(
                List.of(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1), null);
        given(investmentPointsQuery.fetch(eq(USER_ID), eq(InvestmentPointsQuery.Scope.PORTFOLIO),
                isNull(), any(), any(), eq(BenchmarkGranularity.MONTHLY)))
                .willReturn(response);

        mockMvc.perform(get("/api/internal/trading/stats/investment-points")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString())
                        .param("scope", "PORTFOLIO")
                        .param("from", "2026-01-01")
                        .param("to", "2026-09-01")
                        .param("granularity", "MONTHLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2026-09-01"))
                .andExpect(jsonPath("$.selectedStrategy").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/trading/stats/investment-points")
                        .param("userId", USER_ID.toString())
                        .param("scope", "PORTFOLIO")
                        .param("granularity", "MONTHLY"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 필수_파라미터가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/trading/stats/investment-points")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString()))
                .andExpect(status().isBadRequest());
    }
}
