package com.kista.adapter.in.web;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.model.asset.AssetCategory;
import com.kista.domain.model.asset.RegisterAssetCommand;
import com.kista.domain.port.in.AssetUseCase;
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

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
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

@WebMvcTest(AssetController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AssetControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean AssetUseCase assetUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Asset asset(UUID id, UUID userId) {
        return new Asset(id, userId, LocalDate.of(2026, 8, 1), AssetCategory.INVESTMENT,
                "연금저축펀드", "미래에셋증권", "미국주식", "VR", 1_000_000L, null);
    }

    @Test
    void list_assets_returns_200() throws Exception {
        when(assetUseCase.listByUser(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/assets")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_assets_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_returns_201() throws Exception {
        when(assetUseCase.register(any(UUID.class), any(RegisterAssetCommand.class)))
                .thenReturn(asset(UUID.randomUUID(), USER_ID));

        mockMvc.perform(post("/api/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryDate":"2026-08-01","category":"INVESTMENT","subcategory":"연금저축펀드",
                                 "institution":"미래에셋증권","assetClass":"미국주식","strategy":"VR","amount":1000000}
                                """)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("INVESTMENT"));
    }

    @Test
    void register_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryDate":"2026-08-01","category":"INVESTMENT","subcategory":"연금저축펀드",
                                 "assetClass":"미국주식","amount":-1}
                                """)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_by_non_owner_returns403() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(assetUseCase.update(eq(assetId), eq(USER_ID), any()))
                .thenThrow(new SecurityException("자산 기록에 대한 접근 권한이 없습니다"));

        mockMvc.perform(put("/api/assets/{id}", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryDate":"2026-08-01","category":"INVESTMENT","subcategory":"연금저축펀드",
                                 "assetClass":"미국주식","amount":1000000}
                                """)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_not_found_returns404() throws Exception {
        UUID assetId = UUID.randomUUID();
        doThrow(new NoSuchElementException("자산 기록을 찾을 수 없습니다: " + assetId))
                .when(assetUseCase).delete(assetId, USER_ID);

        mockMvc.perform(delete("/api/assets/{id}", assetId)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_success_returns204() throws Exception {
        UUID assetId = UUID.randomUUID();

        mockMvc.perform(delete("/api/assets/{id}", assetId)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(assetUseCase).delete(assetId, USER_ID);
    }
}
