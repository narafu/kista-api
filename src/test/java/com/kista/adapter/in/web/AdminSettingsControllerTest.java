package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.settings.BenchmarkFieldSettings;
import com.kista.domain.model.settings.BenchmarkSettings;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.port.in.AdminSettingsUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.adminToken;
import static com.kista.support.WebMvcTestSupport.userTokenWithRole;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminSettingsController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminSettingsControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminSettingsUseCase adminSettingsUseCase; // 관리자 설정 유스케이스 대역
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean AppErrorLogPort appErrorLogPort;

    @Test
    void getSettings_requiresAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSettings_returnsTypedSettingsForAdmin() throws Exception {
        when(adminSettingsUseCase.getSettings()).thenReturn(RuntimeSettings.defaults());

        mockMvc.perform(get("/api/admin/settings").with(authentication(adminToken(ADMIN_ID))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.auth.approvalRequired").value(true));
    }

    @Test
    void getSettings_rejectsNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/settings")
                        .with(authentication(userTokenWithRole(UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void putSettings_validatesWholePayloadBeforeCallingUseCase() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auth":{"approvalRequired":false},"brokers":{},"strategies":{}}
                                """))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_savesCompletePayload() throws Exception {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettings updated = new RuntimeSettings(false, defaults.brokers(), defaults.strategies(), null);
        when(adminSettingsUseCase.updateSettings(eq(ADMIN_ID), any(), eq(false))).thenReturn(updated);

        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":false},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.auth.approvalRequired").value(false));

        verify(adminSettingsUseCase).updateSettings(ADMIN_ID, updated, false);
    }

    @Test
    void putSettings_savesProvidedBenchmarks() throws Exception {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        BenchmarkSettings customBenchmarks = new BenchmarkSettings(
                new BenchmarkFieldSettings<>(List.of("SPY", "QQQ"), "QQQ"));
        RuntimeSettings updated = new RuntimeSettings(true, defaults.brokers(), defaults.strategies(),
                customBenchmarks);
        when(adminSettingsUseCase.updateSettings(eq(ADMIN_ID), any(), eq(true))).thenReturn(updated);

        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":true},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}}
                                  },
                                  "benchmarks": {"etf": {"allowedValues": ["SPY", "QQQ"], "defaultValue": "QQQ"}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(adminSettingsUseCase).updateSettings(ADMIN_ID, updated, true);
    }

    @Test
    void putSettings_rejectsFixedRecurringModeOtherThanHold() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":true},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":false,"allowedValues":["DEPOSIT"],"defaultValue":"DEPOSIT"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_rejectsNonPositiveDivisionCount() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":true},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[0,20,30],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_rejectsNonPositiveBandWidth() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":true},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[0,10,15],"defaultValue":10},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_rejectsNonPositiveIntervalWeeks() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":true},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[0,1,2],"defaultValue":1}}}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_rejectsUnsupportedBenchmarkSymbol() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":true},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":true},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}}
                                  },
                                  "benchmarks": {"etf": {"allowedValues": ["SPY", "TSLA"], "defaultValue": "SPY"}}
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_rejectsNonAdminUser() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(userTokenWithRole(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
