package com.kista.admin.adapter.in.web;

import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;
import com.kista.admin.domain.model.AdminPrivacyTradeConflictException;
import com.kista.admin.application.usecase.AdminPrivacyTradeUseCase;
import com.kista.admin.application.usecase.AdminQueryUseCase;
import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.admin.application.port.output.AppErrorLogPort;

@WebMvcTest(AdminPrivacyTradeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminPrivacyTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminQueryUseCase adminQuery;
    @MockitoBean AdminPrivacyTradeUseCase adminPrivacyTrade;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listBases_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/privacy-trade-bases"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listBases_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/privacy-trade-bases")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listBases_adminRange30_returns200_andPassesDays30() throws Exception {
        var view = new AdminPrivacyTradeBaseView(UUID.randomUUID(), LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("28.50"), new BigDecimal("45.20"), new BigDecimal("27.80"), 120,
                List.of(new AdminPrivacyTradeBaseView.OrderLine(UUID.randomUUID(), "BUY", "LOC", new BigDecimal("14.25"), 60)));
        when(adminQuery.listPrivacyBases(30)).thenReturn(List.of(view));

        mockMvc.perform(get("/api/admin/privacy-trade-bases?range=30")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].releaseDate").value("2026-06-10"))
                .andExpect(jsonPath("$[0].tradeDate").doesNotExist())
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].orders[0].direction").value("BUY"));
        verify(adminQuery).listPrivacyBases(30);
    }

    @Test
    void listBases_defaultRange_passesNull() throws Exception {
        when(adminQuery.listPrivacyBases(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/privacy-trade-bases")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk());
        verify(adminQuery).listPrivacyBases(isNull());
    }

    @Test
    void listBases_invalidRange_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/privacy-trade-bases?range=7")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBase_신규저장이면_201을_반환한다() throws Exception {
        UUID id = UUID.randomUUID();
        var view = new AdminPrivacyTradeBaseView(id, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("28.50"), new BigDecimal("0"), null, 0, List.of());
        when(adminPrivacyTrade.createBase(eq(ADMIN_UUID), any()))
                .thenReturn(new AdminPrivacyTradeUseCase.CreateResult(view, true));

        mockMvc.perform(post("/api/admin/privacy-trade-bases")
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                            {"releaseDate":"2026-06-10","ticker":"SOXL","currentCycleStart":28.50,
                             "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void createBase_기존동일데이터면_200을_반환한다() throws Exception {
        UUID id = UUID.randomUUID();
        var view = new AdminPrivacyTradeBaseView(id, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("28.50"), new BigDecimal("0"), null, 0, List.of());
        when(adminPrivacyTrade.createBase(eq(ADMIN_UUID), any()))
                .thenReturn(new AdminPrivacyTradeUseCase.CreateResult(view, false));

        mockMvc.perform(post("/api/admin/privacy-trade-bases")
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                            {"releaseDate":"2026-06-10","ticker":"SOXL","currentCycleStart":28.50,
                             "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                            """))
                .andExpect(status().isOk());
    }

    // 같은 (releaseDate, ticker)에 내용이 다른 데이터가 이미 있으면 서비스가 AdminPrivacyTradeConflictException을
    // 던진다 — GlobalExceptionHandler(MAPPINGS에 등록된 own-type)가 이를 409로 변환하는 전체 왕복을 검증한다.
    // PrivacyQueryHttpAdapterTest는 내부 API 409 응답 -> 예외 변환 절반만 검증하므로 이 테스트가 나머지 절반을 메운다.
    @Test
    void createBase_충돌하면_409를_반환한다() throws Exception {
        when(adminPrivacyTrade.createBase(eq(ADMIN_UUID), any()))
                .thenThrow(new AdminPrivacyTradeConflictException("같은 날짜/종목에 내용이 다른 데이터가 존재합니다"));

        mockMvc.perform(post("/api/admin/privacy-trade-bases")
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                            {"releaseDate":"2026-06-10","ticker":"SOXL","currentCycleStart":28.50,
                             "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                            """))
                .andExpect(status().isConflict());
    }

    @Test
    void updateBase_요청을_위임하고_결과를_반환한다() throws Exception {
        UUID id = UUID.randomUUID();
        var view = new AdminPrivacyTradeBaseView(id, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("30.00"), new BigDecimal("5.00"), new BigDecimal("29.00"), 100, List.of());
        when(adminPrivacyTrade.updateBase(eq(ADMIN_UUID), eq(id), any())).thenReturn(view);

        mockMvc.perform(patch("/api/admin/privacy-trade-bases/{id}", id)
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                            {"currentCycleStart":30.00,"currentCycleRealizedPnl":5.00,"avgPrice":29.00,"holdings":100}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdings").value(100));
    }

    @Test
    void updateOrder_요청을_위임하고_결과를_반환한다() throws Exception {
        UUID baseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var view = new AdminPrivacyTradeBaseView(baseId, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("30.00"), new BigDecimal("5.00"), new BigDecimal("29.00"), 100, List.of());
        when(adminPrivacyTrade.updateOrder(eq(ADMIN_UUID), eq(baseId), eq(orderId), any())).thenReturn(view);

        mockMvc.perform(patch("/api/admin/privacy-trade-bases/{baseId}/orders/{orderId}", baseId, orderId)
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                            {"price":31.00,"quantity":15}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(baseId.toString()));
    }
}
