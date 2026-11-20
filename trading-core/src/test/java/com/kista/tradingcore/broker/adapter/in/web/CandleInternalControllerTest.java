package com.kista.tradingcore.broker.adapter.in.web;

import com.kista.tradingcore.broker.adapter.out.marketdata.MarketDataClient;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery.Adjustment;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

// root market 모듈이 소비하는 내부 전용 캔들 엔드포인트 — MarketDataClient(RAW)로 올바르게 위임하는지만 검증
@WebMvcTest(CandleInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class CandleInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean MarketDataClient marketData;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void latest_포트에_그대로_위임한다() throws Exception {
        BrokerCandle candle = new BrokerCandle(LocalDate.of(2026, 1, 2),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L);
        given(marketData.dailyCandles("QQQ", DailyCandleQuery.latest(1, Adjustment.RAW))).willReturn(List.of(candle));

        mockMvc.perform(get("/api/internal/broker/candles/latest")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("symbol", "QQQ")
                        .param("count", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].close").value(10));

        verify(marketData).dailyCandles("QQQ", DailyCandleQuery.latest(1, Adjustment.RAW));
    }

    @Test
    void latest_count는_1에서_600으로_자른다() throws Exception {
        given(marketData.dailyCandles("QQQ", DailyCandleQuery.latest(600, Adjustment.RAW))).willReturn(List.of());
        given(marketData.dailyCandles("QQQ", DailyCandleQuery.latest(1, Adjustment.RAW))).willReturn(List.of());

        mockMvc.perform(get("/api/internal/broker/candles/latest").header("X-Internal-Token", VALID_TOKEN)
                .param("symbol", "QQQ").param("count", "10000")).andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/broker/candles/latest").header("X-Internal-Token", VALID_TOKEN)
                .param("symbol", "QQQ").param("count", "0")).andExpect(status().isOk());

        verify(marketData).dailyCandles("QQQ", DailyCandleQuery.latest(600, Adjustment.RAW));
        verify(marketData).dailyCandles("QQQ", DailyCandleQuery.latest(1, Adjustment.RAW));
    }

    @Test
    void token_없으면_401() throws Exception {
        mockMvc.perform(get("/api/internal/broker/candles/latest")
                        .param("symbol", "QQQ")
                        .param("count", "1"))
                .andExpect(status().isUnauthorized());
    }
}
