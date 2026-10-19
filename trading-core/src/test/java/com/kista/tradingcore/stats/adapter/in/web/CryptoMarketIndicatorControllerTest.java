package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.usecase.CryptoMarketIndicatorUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators.*;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CryptoMarketIndicatorController.class)
@Execution(ExecutionMode.SAME_THREAD)
class CryptoMarketIndicatorControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CryptoMarketIndicatorUseCase useCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static CryptoMarketIndicators sample(FundingRate funding) {
        var oi = new OpenInterest(List.of(new DatedValue(LocalDate.of(2026, 10, 18), new BigDecimal("1234567890"))));
        return new CryptoMarketIndicators(Instant.parse("2026-10-19T00:00:00Z"), null,
                List.of(new TickerIndicators(StrategyTicker.BTC, null, funding, oi)));
    }

    @Test
    void 지표를_조회하면_200과_캐시헤더() throws Exception {
        when(useCase.get(200)).thenReturn(sample(null));

        mockMvc.perform(get("/api/crypto-market-indicators").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=600")))
                .andExpect(jsonPath("$.tickers[0].ticker").value("BTC"))
                .andExpect(jsonPath("$.tickers[0].openInterest.history[0].valueUsd").value(1234567890));
    }

    @Test
    void days_기본값은_200() throws Exception {
        when(useCase.get(200)).thenReturn(sample(null));

        mockMvc.perform(get("/api/crypto-market-indicators").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk());

        verify(useCase).get(200);
    }

    @Test
    void 섹션이_null이면_null로_직렬화() throws Exception {
        when(useCase.get(30)).thenReturn(sample(null));

        mockMvc.perform(get("/api/crypto-market-indicators").param("days", "30").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdtPremium").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].fundingRate").value(nullValue()));
    }
}
