package com.kista.notify.adapter.in.web;

import com.kista.user.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.user.adapter.in.web.security.JwtAuthFilter;
import com.kista.user.adapter.in.web.security.SecurityConfig;
import com.kista.notify.adapter.out.sse.SseEmitterRegistry;
import com.kista.user.application.usecase.BlacklistUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.admin.application.port.output.AppErrorLogPort;

@WebMvcTest(StatusStreamController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class StatusStreamControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean SseEmitterRegistry sseEmitterRegistry;

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void statusStream_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/auth/status-stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusStream_authenticated_returns_200() throws Exception {
        when(sseEmitterRegistry.connect(any())).thenReturn(new SseEmitter());

        // SseEmitter는 MockMvc 내에서 비동기 result를 즉시 설정하지 않으므로 상태코드만 검증
        mockMvc.perform(get("/api/auth/status-stream")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isOk());
    }
}
