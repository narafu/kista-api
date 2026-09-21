package com.kista.trading.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.trading.application.usecase.ManualTradeCorrectionUseCase;
import com.kista.trading.application.usecase.ReorderUseCase;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// reorder/trade-corrections의 @Valid가 실제로 동작하는지 검증 — ReorderCommand/ManualTradeCorrectionCommand에
// jakarta 검증 애노테이션이 없던 시절엔 @Valid가 no-op이라 잘못된 요청이 그대로 서비스까지 흘러가
// 500(NPE 등)으로 뭉개졌다. 여기서는 필수 필드 누락 시 클린한 400을 확인한다.
@WebMvcTest(TradingInternalCommandController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class TradingInternalCommandControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean ReorderUseCase reorderUseCase;
    @MockitoBean ManualTradeCorrectionUseCase manualTradeCorrectionUseCase;
    @MockitoBean MarketCalendarPort marketCalendarPort;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void reorder_필수필드_누락시_400() throws Exception {
        mockMvc.perform(post("/api/internal/trading/reorder")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reorder_quantity_0이하면_400() throws Exception {
        ReorderCommand command = new ReorderCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), OrderTiming.AT_CLOSE, LocalDate.of(2026, 7, 1), OrderDirection.SELL,
                0, new BigDecimal("250.00"), null);

        mockMvc.perform(post("/api/internal/trading/reorder")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctManualFills_필수필드_누락시_400() throws Exception {
        mockMvc.perform(post("/api/internal/trading/trade-corrections")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctManualFills_fills_빈배열이면_400() throws Exception {
        ManualTradeCorrectionCommand command = new ManualTradeCorrectionCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());

        mockMvc.perform(post("/api/internal/trading/trade-corrections")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reorderTimingAvailability_비개장일이면_3개_전부_false() throws Exception {
        when(marketCalendarPort.isMarketOpen(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        mockMvc.perform(get("/api/internal/trading/reorder-timing-availability")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atOpen").value(false))
                .andExpect(jsonPath("$.atClose").value(false))
                .andExpect(jsonPath("$.immediate").value(false));
    }

    @Test
    void reorderTimingAvailability_개장일이면_DstInfo_계산값을_반환() throws Exception {
        when(marketCalendarPort.isMarketOpen(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        mockMvc.perform(get("/api/internal/trading/reorder-timing-availability")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atOpen").isBoolean())
                .andExpect(jsonPath("$.atClose").isBoolean())
                .andExpect(jsonPath("$.immediate").isBoolean());
    }
}
