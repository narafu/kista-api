package com.kista.matching.adapter.in.web;

import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.sharedkernel.StrategyType;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.trading.TradingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StrategyCapabilityInternalController.class)
@ContextConfiguration(classes = TradingApplication.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class StrategyCapabilityInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean CycleOrderStrategies cycleStrategies;

    private static final String VALID_TOKEN = "test-internal-token";

    // root의 StrategyCapability(requiresPrivacyBase, supportsReverseMode, divisionCounts)가
    // 그대로 역직렬화할 수 있는 JSON 필드 이름/구조인지 검증 — @PathVariable StrategyType 바인딩도 함께 확인
    @Test
    void 전략_capability를_조회한다() throws Exception {
        CycleOrderStrategy infinite = mock(CycleOrderStrategy.class);
        given(infinite.requiresPrivacyBase()).willReturn(false);
        given(infinite.supportsReverseMode()).willReturn(true);
        given(infinite.availableDivisionCounts()).willReturn(List.of(20, 30, 40));
        given(cycleStrategies.of(StrategyType.INFINITE)).willReturn(infinite);

        mockMvc.perform(get("/api/internal/matching/strategy-capabilities/{type}", "INFINITE")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresPrivacyBase").value(false))
                .andExpect(jsonPath("$.supportsReverseMode").value(true))
                .andExpect(jsonPath("$.divisionCounts").isArray())
                .andExpect(jsonPath("$.divisionCounts.length()").value(3))
                .andExpect(jsonPath("$.divisionCounts[0]").value(20));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/matching/strategy-capabilities/{type}", "INFINITE"))
                .andExpect(status().isUnauthorized());
    }
}
