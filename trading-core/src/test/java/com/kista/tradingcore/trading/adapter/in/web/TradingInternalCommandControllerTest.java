package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.support.FixedClockTestConfig;
import tools.jackson.databind.ObjectMapper;
import com.kista.tradingcore.trading.application.usecase.ManualTradeCorrectionUseCase;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.platform.security.TokenBlacklistPort;
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

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// trade-corrections의 @Valid가 실제로 동작하는지 검증 — ManualTradeCorrectionCommand에
// jakarta 검증 애노테이션이 없던 시절엔 @Valid가 no-op이라 잘못된 요청이 그대로 서비스까지 흘러가
// 500(NPE 등)으로 뭉개졌다. 여기서는 필수 필드 누락 시 클린한 400을 확인한다.
@WebMvcTest(TradingInternalCommandController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class, FixedClockTestConfig.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class TradingInternalCommandControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean ManualTradeCorrectionUseCase manualTradeCorrectionUseCase;

    private static final String VALID_TOKEN = "test-internal-token";

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
}
