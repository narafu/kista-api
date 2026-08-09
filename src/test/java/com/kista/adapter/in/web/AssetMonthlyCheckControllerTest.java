package com.kista.adapter.in.web;

import com.kista.domain.model.asset.AssetMonthlyCheck;
import com.kista.domain.port.in.AssetMonthlyCheckUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssetMonthlyCheckController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AssetMonthlyCheckControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean AssetMonthlyCheckUseCase assetMonthlyCheckUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_returns_200() throws Exception {
        when(assetMonthlyCheckUseCase.listByUser(any())).thenReturn(List.of(new AssetMonthlyCheck("2026-08", true)));

        mockMvc.perform(get("/api/asset-monthly-checks")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("2026-08"))
                .andExpect(jsonPath("$[0].completed").value(true));
    }

    @Test
    void list_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/asset-monthly-checks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setCompleted_returns_200() throws Exception {
        when(assetMonthlyCheckUseCase.setCompleted(USER_ID, "2026-08", true))
                .thenReturn(new AssetMonthlyCheck("2026-08", true));

        mockMvc.perform(put("/api/asset-monthly-checks/{month}", "2026-08")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void setCompleted_invalidMonth_returns400() throws Exception {
        // '/'가 포함된 값은 라우팅 단계에서 별도 경로 세그먼트로 쪼개져 {month}에 도달하지 못하므로,
        // 단일 세그먼트이지만 의미상 유효하지 않은 값(범위 초과 월)으로 서비스 계층 검증 실패를 재현한다.
        when(assetMonthlyCheckUseCase.setCompleted(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new DateTimeParseException("올바른 연월 형식이 아닙니다", "2026-13", 0));

        mockMvc.perform(put("/api/asset-monthly-checks/{month}", "2026-13")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }
}
