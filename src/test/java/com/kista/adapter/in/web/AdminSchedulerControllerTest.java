package com.kista.adapter.in.web;

import com.kista.adapter.in.schedule.TradingCloseScheduler;
import com.kista.adapter.in.schedule.TradingOpenScheduler;
import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 스케쥴러 빈이 정상 등록된 상태에서의 수동 트리거 API 검증
@WebMvcTest(AdminSchedulerController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminSchedulerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean
    private JwtDecoder jwtDecoder; // JwtDecoderConfig의 실제 빈 생성 방지 + JwtAuthFilter 의존성 주입용
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성

    @MockitoBean private TradingOpenScheduler openScheduler;
    @MockitoBean private TradingCloseScheduler closeScheduler;

    private static final java.util.UUID ADMIN_UUID = DEV_ADMIN_UUID;
    private static final java.util.UUID USER_UUID = DEV_USER_UUID;

    @Test
    void triggerOpen_adminToken_returns202AndRunsScheduler() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/open")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        // 가상 스레드에서 비동기 실행되므로 timeout으로 대기 후 검증
        verify(openScheduler, timeout(2000)).runNow();
    }

    @Test
    void triggerClose_adminToken_returns202AndRunsScheduler() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/close")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        verify(closeScheduler, timeout(2000)).runNow();
    }

    @Test
    void triggerOpen_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/open")
                        .with(csrf())
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isForbidden());
    }
}
