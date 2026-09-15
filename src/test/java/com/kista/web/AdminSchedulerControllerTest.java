package com.kista.web;

import com.kista.stats.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.stats.adapter.in.schedule.KbLandPriceIndexScheduler;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.admin.application.port.output.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

// 스케쥴러 빈이 정상 등록된 상태(kista-scheduler role)에서의 수동 트리거 API 검증
// trading 개장/마감 트리거 케이스는 AdminTradingSchedulerControllerTest로 이관됨
@WebMvcTest(AdminSchedulerController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminSchedulerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean
    private JwtDecoder jwtDecoder; // JwtDecoderConfig의 실제 빈 생성 방지 + JwtAuthFilter 의존성 주입용
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성

    @MockitoBean private KbLandHousingBenchmarkScheduler kbLandScheduler;
    @MockitoBean private KbLandPriceIndexScheduler kbLandPriceIndexScheduler;

    private static final java.util.UUID ADMIN_UUID = DEV_ADMIN_UUID;
    private static final java.util.UUID USER_UUID = DEV_USER_UUID;

    @Test
    void triggerKbLandHousingBenchmark_adminToken_returns202AndRunsScheduler() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/kbland-housing-benchmark")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        verify(kbLandScheduler, timeout(2000)).runNow();
    }

    @Test
    void triggerKbLandPriceIndex_adminToken_returns202AndRunsScheduler() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/kbland-price-index")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        verify(kbLandPriceIndexScheduler, timeout(2000)).runNow();
    }

    @Test
    void triggerKbLandPriceIndexFullRefresh_adminToken_returns202AndRunsScheduler() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/kbland-price-index/full-refresh")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isAccepted());

        verify(kbLandPriceIndexScheduler, timeout(2000)).runFullRefreshNow();
    }

    @Test
    void triggerKbLandHousingBenchmark_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/kbland-housing-benchmark")
                        .with(csrf())
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isForbidden());
    }
}
