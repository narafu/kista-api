package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.RuntimeSettingsUseCase;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RuntimeConfigController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class RuntimeConfigControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RuntimeSettingsUseCase runtimeSettingsUseCase; // 공개 설정 조회 대역
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean AppErrorLogPort appErrorLogPort;

    @Test
    void getRuntimeConfig_isPublicAndDisablesCaching() throws Exception {
        when(runtimeSettingsUseCase.getSettings()).thenReturn(RuntimeSettings.defaults());

        mockMvc.perform(get("/api/runtime-config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.auth.approvalRequired").value(true))
                .andExpect(jsonPath("$.brokers.KIS.enabled").value(true))
                .andExpect(jsonPath("$.strategies.INFINITE.fields.divisionCount.allowedValues[2]").value(40))
                .andExpect(jsonPath("$.strategies.VR.fields.recurringMode.defaultValue").value("HOLD"))
                .andExpect(jsonPath("$.strategies.INFINITE.fields.recurringMode").doesNotExist())
                .andExpect(jsonPath("$.strategies.PRIVACY.fields.divisionCount").doesNotExist())
                .andExpect(jsonPath("$.strategies.PRIVACY.fields.bandWidth").doesNotExist());
    }
}
