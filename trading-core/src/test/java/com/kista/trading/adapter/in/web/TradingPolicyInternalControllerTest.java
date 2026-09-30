package com.kista.trading.adapter.in.web;

import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.sharedkernel.TradingPolicySettings;
import com.kista.trading.application.usecase.TradingPolicyUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// root admin(TradingPolicyHttpAdapter)이 소비하는 매매 런타임 정책 내부 엔드포인트 — X-Internal-Token 인증
@WebMvcTest(TradingPolicyInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class TradingPolicyInternalControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean TradingPolicyUseCase tradingPolicyUseCase;

    private static final String VALID_TOKEN = "test-internal-token";

    private static final String FULL_BODY = """
            {
              "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":false},"MOCK":{"enabled":true}},
              "strategies":{
                "INFINITE":{"enabled":true,"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}},
                "PRIVACY":{"enabled":false,"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}},
                "VR":{"enabled":true,"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}
              }
            }
            """;

    @Test
    void getPolicy_validToken_returnsCurrentPolicy() throws Exception {
        given(tradingPolicyUseCase.getPolicy()).willReturn(TradingPolicySettings.defaults());

        mockMvc.perform(get("/api/internal/trading/policy-settings").header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers.KIS.enabled").value(true))
                .andExpect(jsonPath("$.strategies.INFINITE.divisionCount.allowedValues[2]").value(40))
                .andExpect(jsonPath("$.strategies.VR.recurringMode.defaultValue").value("HOLD"));
    }

    @Test
    void getPolicy_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/trading/policy-settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replacePolicy_validBody_delegatesWholeSettings() throws Exception {
        given(tradingPolicyUseCase.replacePolicy(any())).willAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/internal/trading/policy-settings")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers.TOSS.enabled").value(false))
                .andExpect(jsonPath("$.strategies.PRIVACY.enabled").value(false));

        ArgumentCaptor<TradingPolicySettings> captor = ArgumentCaptor.forClass(TradingPolicySettings.class);
        verify(tradingPolicyUseCase).replacePolicy(captor.capture());
        assertThat(captor.getValue().brokerEnabled(com.kista.sharedkernel.Broker.TOSS)).isFalse();
        assertThat(captor.getValue().strategy(com.kista.sharedkernel.StrategyType.PRIVACY).enabled()).isFalse();
    }

    // enum 키가 빠진 body는 TradingPolicySettings 생성자 검증(IllegalArgumentException)이 거절 — 400
    @Test
    void replacePolicy_missingBrokerKey_returns400WithoutDelegation() throws Exception {
        mockMvc.perform(put("/api/internal/trading/policy-settings")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brokers":{"KIS":{"enabled":true}},"strategies":{}}
                                """))
                .andExpect(status().isBadRequest());

        verify(tradingPolicyUseCase, never()).replacePolicy(any());
    }
}
