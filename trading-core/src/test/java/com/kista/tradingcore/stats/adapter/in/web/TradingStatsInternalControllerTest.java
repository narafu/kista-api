package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;
import com.kista.tradingcore.stats.application.usecase.BrokerStatisticsUseCase;
import com.kista.tradingcore.stats.application.usecase.InvestmentPointsQuery;
import com.kista.tradingcore.stats.domain.model.InvestmentPointsResult;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    @MockitoBean BrokerStatisticsUseCase brokerStatisticsUseCase; // 환율 조회 라우트 의존성

    private static final String VALID_TOKEN = "test-internal-token";
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void 투자_성과_시리즈를_조회한다() throws Exception {
        InvestmentPointsResult response = new InvestmentPointsResult(
                List.of(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1), null);
        given(investmentPointsQuery.fetch(eq(USER_ID), eq(InvestmentPointsQuery.Scope.PORTFOLIO),
                isNull(), any(), any()))
                .willReturn(response);

        mockMvc.perform(get("/api/internal/trading/stats/investment-points")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString())
                        .param("scope", "PORTFOLIO")
                        .param("from", "2026-01-01")
                        .param("to", "2026-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2026-09-01"))
                .andExpect(jsonPath("$.selectedStrategy").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 구버전_root가_보내는_granularity_파라미터는_무시한다() throws Exception {
        // 배포 스큐(새 kista-trading + 구 kista-api) 구간에도 400 없이 응답해야 한다
        given(investmentPointsQuery.fetch(eq(USER_ID), eq(InvestmentPointsQuery.Scope.PORTFOLIO),
                isNull(), isNull(), isNull()))
                .willReturn(new InvestmentPointsResult(
                        List.of(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1), null));

        mockMvc.perform(get("/api/internal/trading/stats/investment-points")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("userId", USER_ID.toString())
                        .param("scope", "PORTFOLIO")
                        .param("granularity", "WEEKLY"))
                .andExpect(status().isOk());
    }

    @Test
    void 현재_환율은_매매기준율_단일_값을_반환한다() throws Exception {
        given(brokerStatisticsUseCase.currentExchangeRate())
                .willReturn(new ExchangeRateQuote(new java.math.BigDecimal("1380.50"), new java.math.BigDecimal("1375.00")));

        mockMvc.perform(get("/api/internal/trading/stats/exchange-rate")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("1375.00"));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/trading/stats/investment-points")
                        .param("userId", USER_ID.toString())
                        .param("scope", "PORTFOLIO"))
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
