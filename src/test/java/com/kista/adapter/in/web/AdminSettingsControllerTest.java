package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.settings.AssetFormOptions;
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

    // institutionSuggestions를 AssetFormOptions.defaults()와 다르게 둔다 — putSettings_savesCompletePayload가
    // 요청→도메인 변환이 실제로 이 값을 반영하는지(예: toDomain()이 요청을 무시하고 defaults()를 반환하는 회귀)
    // 검증할 수 있으려면 최소 한 필드는 기본값과 구분돼야 한다.
    private static final String ASSET_FORM_OPTIONS_JSON = """
            {
              "subcategorySuggestions": {
                "INVESTMENT": ["연금저축펀드","IRP","ISA","일반계좌","거래소","개인지갑"],
                "SAVINGS": ["주택청약종합저축","연금저축보험"],
                "LOAN": ["전세자금대출","신용대출","오토할부"],
                "REAL_ESTATE": ["전세임차보증금"]
              },
              "institutionSuggestions": ["테스트기관"],
              "assetClassSuggestions": ["미국주식","기타주식","금/은","크립토","원화","달러"],
              "strategySuggestions": ["VR","INFINITE","PRIVACY","DCA"]
            }
            """;

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
                .andExpect(jsonPath("$.auth.approvalRequired").value(true))
                .andExpect(jsonPath("$.assetFormOptions.institutionSuggestions").isArray())
                .andExpect(jsonPath("$.assetFormOptions.institutionSuggestions[0]").value("토스증권"))
                .andExpect(jsonPath("$.assetFormOptions.assetClassSuggestions[0]").value("미국주식"))
                .andExpect(jsonPath("$.assetFormOptions.strategySuggestions[0]").value("VR"))
                .andExpect(jsonPath("$.assetFormOptions.subcategorySuggestions.INVESTMENT[0]").value("연금저축펀드"));
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
        // institutionSuggestions만 커스텀 — ASSET_FORM_OPTIONS_JSON과 동일한 값을 줘서 마지막 verify()가
        // 요청 JSON이 실제로 이 필드까지 반영해 도메인으로 변환됐는지(값을 버리고 defaults()를 반환하는 회귀가
        // 없는지) 검증하게 한다.
        AssetFormOptions customAssetFormOptions = new AssetFormOptions(
                AssetFormOptions.defaults().subcategorySuggestions(), List.of("테스트기관"),
                AssetFormOptions.defaults().assetClassSuggestions(), AssetFormOptions.defaults().strategySuggestions());
        RuntimeSettings updated = new RuntimeSettings(false, defaults.brokers(), defaults.strategies(),
                null, customAssetFormOptions);
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
                                  },
                                  "assetFormOptions": %s
                                }
                                """.formatted(ASSET_FORM_OPTIONS_JSON)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.auth.approvalRequired").value(false))
                .andExpect(jsonPath("$.assetFormOptions.institutionSuggestions[0]").value("테스트기관"));

        verify(adminSettingsUseCase).updateSettings(ADMIN_ID, updated, false);
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
                                  },
                                  "assetFormOptions": %s
                                }
                                """.formatted(ASSET_FORM_OPTIONS_JSON)))
                .andExpect(status().isBadRequest());

        verify(adminSettingsUseCase, never()).updateSettings(any(), any(), anyBoolean());
    }

    @Test
    void putSettings_rejectsWhenAssetFormOptionsMissing() throws Exception {
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
