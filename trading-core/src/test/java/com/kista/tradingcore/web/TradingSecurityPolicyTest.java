package com.kista.tradingcore.web;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig + TradingSecurityPolicy 조합의 trading-core 라우트 인가 결과 — 공개 경로는 GET 지표 하나뿐임을 잠근다
@WebMvcTest(TradingSecurityPolicyTest.ProbeController.class)
@Import({SecurityConfig.class, TradingSecurityPolicy.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
        TradingSecurityPolicyTest.ProbeController.class})
@Execution(ExecutionMode.SAME_THREAD)
class TradingSecurityPolicyTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성

    @RestController
    static class ProbeController {
        @GetMapping("/api/crypto-market-indicators")
        String indicators() { return "indicators"; }

        @PostMapping("/api/crypto-market-indicators")
        String indicatorsPost() { return "post"; }

        @GetMapping("/api/accounts/probe")
        String accounts() { return "accounts"; }
    }

    @Test
    void cryptoMarketIndicators_anonymousGetIs200() throws Exception {
        mockMvc.perform(get("/api/crypto-market-indicators")).andExpect(status().isOk());
    }

    @Test
    void cryptoMarketIndicators_anonymousNonGetIs401() throws Exception {
        mockMvc.perform(post("/api/crypto-market-indicators").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void otherRoute_anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/accounts/probe")).andExpect(status().isUnauthorized());
    }
}
