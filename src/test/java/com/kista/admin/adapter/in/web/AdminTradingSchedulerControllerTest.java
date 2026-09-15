package com.kista.admin.adapter.in.web;

import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.admin.application.port.output.TradingSchedulerCommandPort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// trading 개장/마감 스케쥴러 수동 트리거 — 구 web.AdminSchedulerController open/close 케이스 이관
// (내부 API 호출이라 scheduler.enabled 게이팅 없이 kista-api role에서도 상시 노출)
@WebMvcTest(AdminTradingSchedulerController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminTradingSchedulerControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean TradingSchedulerCommandPort schedulerCommandPort;

    private static final UUID ADMIN_UUID = DEV_ADMIN_UUID;
    private static final UUID USER_UUID = DEV_USER_UUID;

    @Test
    void triggerOpen_adminToken_returns202AndCallsPort() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/open")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        verify(schedulerCommandPort).triggerOpen();
    }

    @Test
    void triggerClose_adminToken_returns202AndCallsPort() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/close")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        verify(schedulerCommandPort).triggerClose();
    }

    @Test
    void triggerOpen_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/open")
                        .with(csrf())
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isForbidden());
    }
}
