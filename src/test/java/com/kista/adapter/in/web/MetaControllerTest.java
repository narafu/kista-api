package com.kista.adapter.in.web;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.domain.strategy.VrCycleOrderStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(MetaController.class)
@Execution(ExecutionMode.SAME_THREAD)
class MetaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean CycleOrderStrategies cycleStrategies;

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        var infinite = new InfiniteCycleOrderStrategy(null, null);
        var privacy = new PrivacyCycleOrderStrategy(null);
        var vr = new VrCycleOrderStrategy(null);
        when(cycleStrategies.of(Strategy.Type.INFINITE)).thenReturn(infinite);
        when(cycleStrategies.of(Strategy.Type.PRIVACY)).thenReturn(privacy);
        when(cycleStrategies.of(Strategy.Type.VR)).thenReturn(vr);
    }

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
    void getBundle_authenticated_includesCycleSeedTypes() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleSeedTypes").isArray())
                .andExpect(jsonPath("$.cycleSeedTypes.length()").value(Strategy.CycleSeedType.values().length));
    }
}
