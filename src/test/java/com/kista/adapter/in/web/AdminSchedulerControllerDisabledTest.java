package com.kista.adapter.in.web;

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

import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// TradingOpenScheduler/TradingCloseScheduler 빈이 컨텍스트에 등록되지 않은 경우
// (scheduler.enabled=false 등으로 @ConditionalOnProperty가 빈 생성을 막은 상황을 재현)
// @Autowired(required = false) 필드가 null로 남아 컨트롤러가 IllegalStateException을 던지고
// GlobalExceptionHandler가 400으로 매핑하는지 검증
@WebMvcTest(AdminSchedulerController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminSchedulerControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean
    private JwtDecoder jwtDecoder; // JwtDecoderConfig의 실제 빈 생성 방지 + JwtAuthFilter 의존성 주입용
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성

    // TradingOpenScheduler/TradingCloseScheduler는 의도적으로 @MockitoBean 미등록
    // -> AdminSchedulerController의 @Autowired(required = false) 필드가 null로 주입됨

    private static final UUID ADMIN_UUID = DEV_ADMIN_UUID;

    @Test
    void triggerOpen_schedulerDisabled_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/open")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void triggerClose_schedulerDisabled_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/close")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isBadRequest());
    }
}
