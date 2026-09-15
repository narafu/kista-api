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
import static org.mockito.Mockito.doThrow;
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

    @Test
    void save_portFailure_stillReturns204() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(appErrorLogPort).save(eq("KisApiException"), eq("연결 오류"), eq((String) null), eq((Map<String, String>) null));

        mockMvc.perform(post("/api/internal/errors")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorType\":\"KisApiException\",\"message\":\"연결 오류\"}"))
                .andExpect(status().isNoContent());
    }
}
