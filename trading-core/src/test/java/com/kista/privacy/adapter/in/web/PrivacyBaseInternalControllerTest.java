package com.kista.privacy.adapter.in.web;

import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.trading.TradingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrivacyBaseInternalController.class)
@ContextConfiguration(classes = TradingApplication.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class PrivacyBaseInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean PrivacyTradePort privacyTradePort;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void 단건_조회_성공() throws Exception {
        UUID id = UUID.randomUUID();
        given(privacyTradePort.findByIdOrThrow(id)).willReturn(mockView(id));

        mockMvc.perform(get("/api/internal/privacy/trade-bases/{baseId}", id)
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void 단건_조회_없으면_404() throws Exception {
        UUID id = UUID.randomUUID();
        given(privacyTradePort.findByIdOrThrow(id))
                .willThrow(new NoSuchElementException("PRIVACY 기준 매매표를 찾을 수 없습니다: " + id));

        mockMvc.perform(get("/api/internal/privacy/trade-bases/{baseId}", id)
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void 마스터_수정_성공() throws Exception {
        UUID id = UUID.randomUUID();
        given(privacyTradePort.updateBase(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any()))
                .willReturn(mockView(id));

        mockMvc.perform(patch("/api/internal/privacy/trade-bases/{baseId}", id)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("""
                            {"currentCycleStart":30.00,"currentCycleRealizedPnl":5.00,"avgPrice":29.00,"holdings":100}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void 주문_명세_수정_성공() throws Exception {
        UUID baseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        given(privacyTradePort.updateOrder(org.mockito.ArgumentMatchers.eq(baseId), org.mockito.ArgumentMatchers.eq(orderId),
                org.mockito.ArgumentMatchers.any())).willReturn(mockView(baseId));

        mockMvc.perform(patch("/api/internal/privacy/trade-bases/{baseId}/orders/{orderId}", baseId, orderId)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("""
                            {"price":31.00,"quantity":15}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(baseId.toString()));
    }

    @Test
    void 내부_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/privacy/trade-bases/{baseId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private PrivacyTradeBaseView mockView(UUID id) {
        return new PrivacyTradeBaseView(id, LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("28.50"), new BigDecimal("0"), null, 0, List.of());
    }
}
