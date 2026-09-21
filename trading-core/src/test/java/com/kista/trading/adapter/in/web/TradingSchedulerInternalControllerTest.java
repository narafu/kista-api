package com.kista.trading.adapter.in.web;

import com.kista.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.trading.adapter.in.schedule.TradingOpenScheduler;
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

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradingSchedulerInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class TradingSchedulerInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean TradingOpenScheduler openScheduler;
    @MockitoBean TradingCloseScheduler closeScheduler;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void 내부_토큰으로_개장_스케쥴러를_트리거한다() throws Exception {
        mockMvc.perform(post("/api/internal/trading/scheduler/open")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isAccepted());

        // 가상 스레드에서 비동기 실행되므로 timeout으로 대기 후 검증
        verify(openScheduler, timeout(2000)).runNow();
    }

    @Test
    void 내부_토큰으로_마감_스케쥴러를_트리거한다() throws Exception {
        mockMvc.perform(post("/api/internal/trading/scheduler/close")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isAccepted());

        verify(closeScheduler, timeout(2000)).runNow();
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/internal/trading/scheduler/open"))
                .andExpect(status().isUnauthorized());
    }
}
