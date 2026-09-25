package com.kista.admin.adapter.in.web;

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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ErrorLogInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class ErrorLogInternalControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void save_validToken_returns204AndSavesLog() throws Exception {
        mockMvc.perform(post("/api/internal/errors")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"errorType":"KisApiException","message":"연결 오류","stackTrace":"at foo()","context":{"caller":"TradingExceptionHandler"}}
                                """))
                .andExpect(status().isNoContent());

        verify(appErrorLogPort).save(eq("KisApiException"), eq("연결 오류"), eq("at foo()"), eq(Map.of("caller", "TradingExceptionHandler")));
    }

    @Test
    void save_missingToken_returns401() throws Exception {
        mockMvc.perform(post("/api/internal/errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // 저장 실패 시 호출부가 여전히 204를 반환하는지는 AppErrorLogPersistenceAdapterTest(격리 계약 실제 구현체)가 검증 —
    // 여기서 mock AppErrorLogPort에 doThrow를 걸어 검증하던 방식은 포트 계약(절대 던지지 않음)과 모순돼 제거
}
