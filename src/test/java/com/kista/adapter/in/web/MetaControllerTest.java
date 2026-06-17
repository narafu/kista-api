package com.kista.adapter.in.web;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.BlacklistUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성

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
        int strategyTypeCount = Strategy.Type.values().length;
        int tickerCount = Strategy.Ticker.values().length;

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
    void getStrategyTypes_authenticated_returnsCodeAndAvailableTickers() throws Exception {
        mockMvc.perform(get("/api/meta/strategy-types")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("INFINITE"))
                .andExpect(jsonPath("$[0].availableTickers").isArray());
    }

    @Test
    void getTickers_authenticated_returnsCode() throws Exception {
        mockMvc.perform(get("/api/meta/tickers")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TQQQ"))
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[0].targetProfitRate").exists());
    }

    @Test
    void getBrokers_authenticated_returnsCodeAndLabel() throws Exception {
        mockMvc.perform(get("/api/meta/brokers")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TOSS"))
                .andExpect(jsonPath("$[0].label").value("토스증권"));
    }

    @Test
    void getStrategyStatuses_authenticated_returnsCodeAndLabel() throws Exception {
        mockMvc.perform(get("/api/meta/strategy-statuses")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ACTIVE"))
                .andExpect(jsonPath("$[0].label").value("운영중"));
    }

    @Test
    void getCycleSeedTypes_authenticated_returnsAllThreeWithLabel() throws Exception {
        mockMvc.perform(get("/api/meta/cycle-seed-types")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Strategy.CycleSeedType.values().length))
                .andExpect(jsonPath("$[0].code").value("NONE"))
                .andExpect(jsonPath("$[0].label").value("OFF"));
    }

    @Test
    void getBundle_authenticated_includesCycleSeedTypes() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleSeedTypes").isArray())
                .andExpect(jsonPath("$.cycleSeedTypes.length()").value(Strategy.CycleSeedType.values().length));
    }
}
