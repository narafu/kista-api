package com.kista.broker.adapter.in.web;

import com.kista.broker.application.port.output.CandlePort;
import com.kista.broker.domain.model.toss.TossCandle;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// root market 모듈이 소비하는 내부 전용 캔들 엔드포인트 — CandlePort로 올바르게 위임하는지만 검증
@WebMvcTest(CandleInternalController.class)
@ContextConfiguration(classes = TradingApplication.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class CandleInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean CandlePort candlePort;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void latest_포트에_그대로_위임한다() throws Exception {
        TossCandle candle = new TossCandle(LocalDate.of(2026, 1, 2),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L);
        given(candlePort.getLatestCandles("QQQ", "1d", 1)).willReturn(List.of(candle));

        mockMvc.perform(get("/api/internal/broker/candles/latest")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("symbol", "QQQ")
                        .param("interval", "1d")
                        .param("count", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].close").value(10));

        verify(candlePort).getLatestCandles("QQQ", "1d", 1);
    }

    @Test
    void token_없으면_401() throws Exception {
        mockMvc.perform(get("/api/internal/broker/candles/latest")
                        .param("symbol", "QQQ")
                        .param("interval", "1d")
                        .param("count", "1"))
                .andExpect(status().isUnauthorized());
    }
}
