package com.kista.web;

import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.kista.support.WebMvcTestSupport.*;
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

    // capability는 sharedkernel 상수에서 직접 조립한다 — 내부 API(RestClient) 없이 JSON shape 유지
    // StrategyType 선언 순서(INFINITE, PRIVACY, VR)대로 직렬화된다
    @Test
    void getBundle_authenticated_strategyTypesCarryCapabilityFields() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyTypes[0].code").value("INFINITE"))
                .andExpect(jsonPath("$.strategyTypes[0].requiresPrivacyBase").value(false))
                .andExpect(jsonPath("$.strategyTypes[0].supportsReverseMode").value(true))
                .andExpect(jsonPath("$.strategyTypes[0].tickerFixed").value(false))
                .andExpect(jsonPath("$.strategyTypes[0].divisionCounts.length()").value(3))
                .andExpect(jsonPath("$.strategyTypes[0].divisionCounts[0]").value(20))
                .andExpect(jsonPath("$.strategyTypes[1].code").value("PRIVACY"))
                .andExpect(jsonPath("$.strategyTypes[1].requiresPrivacyBase").value(true))
                .andExpect(jsonPath("$.strategyTypes[1].tickerFixed").value(true))
                .andExpect(jsonPath("$.strategyTypes[2].code").value("VR"))
                .andExpect(jsonPath("$.strategyTypes[2].availableTickers[0]").value("TQQQ"));
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
