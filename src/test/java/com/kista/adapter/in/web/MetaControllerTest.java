package com.kista.adapter.in.web;

import com.kista.domain.model.tradingcycle.TradingCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetaController.class)
@Execution(ExecutionMode.SAME_THREAD)
class MetaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(USER_UUID, null, List.of());
    }

    @Test
    void getBundle_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBundle_authenticated_returns200WithAllSections() throws Exception {
        int strategyTypeCount = TradingCycle.Type.values().length;
        int tickerCount = TradingCycle.Ticker.values().length;

        mockMvc.perform(get("/api/meta")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyTypes").isArray())
                .andExpect(jsonPath("$.strategyTypes.length()").value(strategyTypeCount))
                .andExpect(jsonPath("$.tickers").isArray())
                .andExpect(jsonPath("$.tickers.length()").value(tickerCount))
                .andExpect(jsonPath("$.brokers").isArray())
                .andExpect(jsonPath("$.strategyStatuses").isArray());
    }

    @Test
    void getStrategyTypes_authenticated_returnsCodeLabelAvailableTickers() throws Exception {
        mockMvc.perform(get("/api/meta/strategy-types")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("INFINITE"))
                .andExpect(jsonPath("$[0].label").value("무한매수"))
                .andExpect(jsonPath("$[0].availableTickers").isArray())
                .andExpect(jsonPath("$[0].defaultTicker").exists())
                .andExpect(jsonPath("$[0].defaultMultiple").exists());
    }

    @Test
    void getTickers_authenticated_returnsCodeAndExchangeCode() throws Exception {
        mockMvc.perform(get("/api/meta/tickers")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TQQQ"))
                .andExpect(jsonPath("$[0].exchangeCode").value("NASD"))
                .andExpect(jsonPath("$[0].label").exists())
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[0].targetProfitRate").exists());
    }

    @Test
    void getBrokers_authenticated_returnsCodeAndLabel() throws Exception {
        mockMvc.perform(get("/api/meta/brokers")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("KIS"))
                .andExpect(jsonPath("$[0].label").value("한국투자증권"));
    }

    @Test
    void getStrategyStatuses_authenticated_returnsCodeAndLabel() throws Exception {
        mockMvc.perform(get("/api/meta/strategy-statuses")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ACTIVE"))
                .andExpect(jsonPath("$[0].label").value("활성"));
    }
}
