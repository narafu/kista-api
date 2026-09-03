package com.kista.finance.adapter.in.web;

import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.application.usecase.BlacklistUseCase;
import com.kista.finance.application.usecase.MonthlyClosingUseCase;
import com.kista.admin.application.port.output.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MonthlyClosingController.class)
@Execution(ExecutionMode.SAME_THREAD)
class MonthlyClosingControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean MonthlyClosingUseCase monthlyClosingUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_returns200() throws Exception {
        MonthlyClosing closing = new MonthlyClosing(UUID.randomUUID(), UUID.randomUUID(), USER_ID,
                "2026-08", true, Instant.now(), Instant.now());
        when(monthlyClosingUseCase.list(any(), any())).thenReturn(List.of(closing));

        mockMvc.perform(get("/api/finance/monthly-closings")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("2026-08"));
    }

    // PATCH /{month}로 상태 전이한다 — plan §6에서 구 AssetMonthlyCheck의 PUT을 의도적으로 PATCH로 바꾼 지점.
    // @PatchMapping이 @PutMapping으로 회귀하면 이 테스트는 405로 실패한다.
    @Test
    void setCompleted_patch_returns200() throws Exception {
        MonthlyClosing closing = new MonthlyClosing(UUID.randomUUID(), UUID.randomUUID(), USER_ID,
                "2026-08", true, Instant.now(), Instant.now());
        when(monthlyClosingUseCase.setCompleted(eq(USER_ID), any(), eq("2026-08"), eq(true)))
                .thenReturn(closing);

        mockMvc.perform(patch("/api/finance/monthly-closings/{month}", "2026-08")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-08"))
                .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void setCompleted_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(patch("/api/finance/monthly-closings/{month}", "2026-08")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }
}
