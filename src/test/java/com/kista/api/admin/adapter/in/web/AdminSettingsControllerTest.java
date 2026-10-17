package com.kista.api.admin.adapter.in.web;

import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.api.admin.domain.model.BenchmarkFieldSettings;
import com.kista.api.admin.domain.model.BenchmarkSettings;
import com.kista.api.admin.domain.model.RuntimeSettings;
import com.kista.api.admin.domain.model.RuntimeSettingsBundle;
import com.kista.shared.kernel.TradingPolicySettings;
import com.kista.api.admin.application.usecase.AdminSettingsUseCase;
import com.kista.shared.platform.security.TokenBlacklistPort;
import com.kista.api.admin.application.port.output.AppErrorLogPort;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.BrokerSettings;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.UUID;
import com.kista.api.web.RootSecurityPolicy;

import static com.kista.shared.support.WebMvcTestSupport.adminToken;
import static com.kista.shared.support.WebMvcTestSupport.userTokenWithRole;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminSettingsController.class)
@Import({SecurityConfig.class, RootSecurityPolicy.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminSettingsControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminSettingsUseCase adminSettingsUseCase; // 관리자 설정 유스케이스 대역
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort;
    @MockitoBean AppErrorLogPort appErrorLogPort;

    @BeforeEach
    void stubCurrentSettings() {
        // PUT은 누락 증권사 키 보존을 위해 현재 정책을 먼저 읽는다
        when(adminSettingsUseCase.getSettings()).thenReturn(RuntimeSettingsBundle.defaults());
    }

    @Test
    void getSettings_requiresAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSettings_returnsTypedSettingsForAdmin() throws Exception {
        when(adminSettingsUseCase.getSettings()).thenReturn(RuntimeSettingsBundle.defaults());

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
        RuntimeSettingsBundle updated = new RuntimeSettingsBundle(
                new RuntimeSettings(false, null), TradingPolicySettings.defaults());
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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
        BenchmarkSettings customBenchmarks = new BenchmarkSettings(
                new BenchmarkFieldSettings<>(List.of("SPY", "QQQ"), "QQQ"));
        RuntimeSettingsBundle updated = new RuntimeSettingsBundle(
                new RuntimeSettings(true, customBenchmarks), TradingPolicySettings.defaults());
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":false,"allowedValues":["DEPOSIT"],"defaultValue":"DEPOSIT"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[0,10,15],"defaultValue":10},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[0,1,2],"defaultValue":1}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
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

    @Test
    void updateSettings_withoutUpbitKey_preservesCurrentUpbitSettings() throws Exception {
        // 현재 저장 값: UPBIT 활성 + 다른 IP — 현행 UI는 brokers에 UPBIT를 보내지 않는다
        java.util.Map<Broker, BrokerSettings> currentBrokers = new java.util.EnumMap<>(TradingPolicySettings.defaults().brokers());
        currentBrokers.put(Broker.UPBIT, new BrokerSettings(true, "10.0.0.1"));
        TradingPolicySettings current = new TradingPolicySettings(currentBrokers, TradingPolicySettings.defaults().strategies());
        when(adminSettingsUseCase.getSettings()).thenReturn(new RuntimeSettingsBundle(RuntimeSettings.defaults(), current));
        when(adminSettingsUseCase.updateSettings(eq(ADMIN_ID), any(), eq(false)))
                .thenAnswer(inv -> inv.getArgument(1));

        mockMvc.perform(put("/api/admin/settings")
                        .with(authentication(adminToken(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auth":{"approvalRequired":false},
                                  "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":false},"MOCK":{"enabled":true}},
                                  "strategies":{
                                    "INFINITE":{"enabled":true,"fields":{"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}}},
                                    "PRIVACY":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}}},
                                    "VR":{"enabled":true,"fields":{"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}},
                                    "DCA":{"enabled":false,"fields":{"ticker":{"customizable":true,"allowedValues":["UPRO","QLD","BTC","ETH"],"defaultValue":"UPRO"}}}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers.UPBIT.enabled").value(true))
                .andExpect(jsonPath("$.brokers.UPBIT.allowedIp").value("10.0.0.1"))
                .andExpect(jsonPath("$.brokers.TOSS.enabled").value(false));
    }
}
