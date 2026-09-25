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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClientErrorLogController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class ClientErrorLogControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort;

    @Test
    void log_isPublicAndReturns204() throws Exception {
        mockMvc.perform(post("/api/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"errorType":"TypeError","message":"boom","stackTrace":"at foo()","context":{"pathname":"/login"}}
                                """))
                .andExpect(status().isNoContent());

        verify(appErrorLogPort).save(eq("TypeError"), eq("boom"), eq("at foo()"), eq(Map.of("pathname", "/login")));
    }

    @Test
    void log_missingErrorType_returns400() throws Exception {
        mockMvc.perform(post("/api/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // 저장 실패 시 호출부가 여전히 204를 반환하는지는 AppErrorLogPersistenceAdapterTest(격리 계약 실제 구현체)가 검증 —
    // 여기서 mock AppErrorLogPort에 doThrow를 걸어 검증하던 방식은 포트 계약(절대 던지지 않음)과 모순돼 제거
}
