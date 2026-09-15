package com.kista.web;

import com.kista.platform.security.TokenBlacklistPort;
import com.kista.web.dto.StrategyCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@WebMvcTest(MetaController.class)
@Execution(ExecutionMode.SAME_THREAD)
class MetaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) RestClient internalApiRestClient;

    @BeforeEach
    void setUp() {
        var capability = new StrategyCapability(false, true, List.of(20, 30, 40));
        when(internalApiRestClient.get()
                .uri(anyString(), any(Object[].class))
                .retrieve()
                .body(StrategyCapability.class))
                .thenReturn(capability);
    }

    @Test
    void getBundle_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBundle_authenticated_returns200WithAllSections() throws Exception {
        int strategyTypeCount = StrategyType.values().length;
        int tickerCount = StrategyTicker.values().length;

        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyTypes").isArray())
                .andExpect(jsonPath("$.strategyTypes.length()").value(strategyTypeCount))
                .andExpect(jsonPath("$.tickers").isArray())
                .andExpect(jsonPath("$.tickers.length()").value(tickerCount))
                .andExpect(jsonPath("$.brokers").isArray())
                .andExpect(jsonPath("$.strategyStatuses").isArray());
    }

    @Test
    void getBundle_authenticated_includesCycleSeedTypes() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleSeedTypes").isArray())
                .andExpect(jsonPath("$.cycleSeedTypes.length()").value(StrategyCycleSeedType.values().length));
    }
}
