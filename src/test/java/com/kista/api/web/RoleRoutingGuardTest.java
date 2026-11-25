package com.kista.api.web;

import com.kista.api.admin.application.port.output.TradingPolicyPort;
import com.kista.shared.platform.internalapi.InternalApiProperties;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarRefreshPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.shared.kernel.TradingPolicySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// role 경계 가드 — web은 엔진(trading-core) 경로, worker는 엔진 아닌 경로를 503 WRONG_ROLE로 거절하고 all은 통과시킨다
class RoleRoutingGuardTest {

    // role별 컨텍스트 공통 — 기동 잡이 실제 Alpaca·api.telegram.org를, /api/meta가 내부 HTTP 정책 조회를 부르지 않게 외부 호출 포트만 막는다
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Execution(ExecutionMode.SAME_THREAD)
    abstract static class Base {
        @Autowired MockMvc mockMvc;
        @MockitoBean MarketCalendarRefreshPort marketCalendarRefreshPort;
        @MockitoBean TradingErrorReportPort tradingErrorReportPort;
        @MockitoBean TradingPolicyPort tradingPolicyPort; // /api/meta 런타임 값 — 로컬 8080에 떠 있는 앱으로 새지 않게

        @BeforeEach
        void stubPolicy() {
            given(tradingPolicyPort.load()).willReturn(TradingPolicySettings.defaults()); // 기본 정책으로 고정
        }

        ResultActions call(String uri) throws Exception {
            return mockMvc.perform(get(uri).header(InternalApiProperties.TOKEN_HEADER, "test-internal-token"));
        }

        void assertWrongRole(String uri) throws Exception {
            call(uri).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("WRONG_ROLE"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.detail").value("이 서버 역할에서는 처리하지 않는 요청입니다."));
        }

        void assertNotWrongRole(String uri) throws Exception {
            assertThat(call(uri).andReturn().getResponse().getStatus()).isNotEqualTo(503);
        }
    }

    @Nested
    @TestPropertySource(properties = "kista.role=web")
    class Web extends Base {
        @Test
        void 엔진_경로는_503() throws Exception {
            assertWrongRole("/api/trading-cycles");
            assertWrongRole("/api/internal/accounts");
        }

        @Test
        void 엔진_아닌_경로와_헬스는_통과() throws Exception {
            call("/api/meta").andExpect(status().isOk());
            call("/actuator/health/liveness").andExpect(status().isOk());
        }

        @Test
        void 매칭_실패는_가드가_삼키지_않고_기존_405가_나온다() throws Exception {
            mockMvc.perform(post("/api/meta").with(csrf()).with(authentication(
                    new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of())))).andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @TestPropertySource(properties = "kista.role=worker")
    class Worker extends Base {
        @Test
        void 엔진_아닌_경로는_503() throws Exception {
            assertWrongRole("/api/finance/accounts");
            assertWrongRole("/api/meta");
        }

        @Test
        void 엔진_경로와_헬스는_통과() throws Exception {
            assertNotWrongRole("/api/internal/accounts");
            call("/actuator/health/liveness").andExpect(status().isOk());
        }
    }

    @Nested
    class All extends Base {
        @Test
        void 어느_경로도_거절하지_않는다() throws Exception {
            assertNotWrongRole("/api/trading-cycles");
            assertNotWrongRole("/api/finance/accounts");
        }
    }
}
