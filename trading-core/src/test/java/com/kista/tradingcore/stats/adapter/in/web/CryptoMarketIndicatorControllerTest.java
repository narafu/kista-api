package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.usecase.CryptoMarketIndicatorUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators.*;
import com.kista.tradingcore.stats.domain.model.DatedHashRibbon;
import com.kista.tradingcore.stats.domain.model.DatedLongShortRatio;
import com.kista.tradingcore.stats.domain.model.DatedPremiumPair;
import com.kista.tradingcore.stats.domain.model.DatedValuation;
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
                List.of(new TickerIndicators(StrategyTicker.BTC, null, funding, oi, null, null, null)), null, null, null, null);
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
    void 테더프리미엄과_펀딩비_섹션이_null이면_null로_직렬화() throws Exception {
        when(useCase.get(30)).thenReturn(sample(null));

        mockMvc.perform(get("/api/crypto-market-indicators").param("days", "30").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdtPremium").value(nullValue()))
                .andExpect(jsonPath("$.halving").value(nullValue()))
                .andExpect(jsonPath("$.btcDominance").value(nullValue()))
                .andExpect(jsonPath("$.altcoinSeason").value(nullValue()))
                .andExpect(jsonPath("$.hashRibbons").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].fundingRate").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].valuation").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].longShortRatio").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].coinbasePremium").value(nullValue()));
    }

    @Test
    void hashRibbons의_SMA와_state가_null이면_null로_직렬화() throws Exception {
        var d = LocalDate.of(2026, 10, 18);
        when(useCase.get(200)).thenReturn(new CryptoMarketIndicators(Instant.parse("2026-10-19T00:00:00Z"), null, List.of(),
                null, null, null,
                HashRibbons.of(List.of(new DatedHashRibbon(d, new BigDecimal("1050.12"), null, null, null, false)))));

        mockMvc.perform(get("/api/crypto-market-indicators").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashRibbons.current.sma30").value(nullValue()))
                .andExpect(jsonPath("$.hashRibbons.current.sma60").value(nullValue()))
                .andExpect(jsonPath("$.hashRibbons.current.state").value(nullValue()))
                .andExpect(jsonPath("$.hashRibbons.current.buySignal").value(false));
    }

    @Test
    void 모든_섹션이_채워지면_값이_매핑된다() throws Exception {
        var d = LocalDate.of(2026, 10, 18);
        var premium = new Premium(new BigDecimal("0.35"), List.of(new DatedValue(d, new BigDecimal("0.3"))));
        var kimchi = new KimchiPremium(new PremiumPair(new BigDecimal("1.5"), new BigDecimal("2.5")),
                List.of(new DatedPremiumPair(d, null, new BigDecimal("2.4"))));
        var funding = new FundingRate(new BigDecimal("0.0001"), Instant.parse("2026-10-19T08:00:00Z"),
                List.of(new DatedValue(d, new BigDecimal("0.00012"))));
        var oi = new OpenInterest(List.of(new DatedValue(d, new BigDecimal("100"))));
        var valuation = Valuation.of(List.of(new DatedValuation(d, new BigDecimal("100000"), null, null,
                new BigDecimal("1.25"), new BigDecimal("-10.5"))));
        var longShort = LongShortRatio.of(List.of(new DatedLongShortRatio(d, new BigDecimal("0.6331"), new BigDecimal("1.7255"))));
        when(useCase.get(200)).thenReturn(new CryptoMarketIndicators(Instant.parse("2026-10-19T00:00:00Z"), premium,
                List.of(new TickerIndicators(StrategyTicker.BTC, kimchi, funding, oi, valuation, longShort,
                        new Premium(new BigDecimal("0.0342"), List.of(new DatedValue(d, new BigDecimal("-0.0125")))))),
                new Halving(972_175L, 1_050_000L, 77_825L, Instant.parse("2028-04-01T00:00:00Z")),
                new DailyIndicator(new BigDecimal("60.1"), List.of(new DatedValue(d, new BigDecimal("60.0")))),
                new DailyIndicator(new BigDecimal("55"), List.of(new DatedValue(d, new BigDecimal("55")))),
                HashRibbons.of(List.of(new DatedHashRibbon(d, new BigDecimal("1050.12"), new BigDecimal("1000"), new BigDecimal("1010"),
                        DatedHashRibbon.State.CAPITULATION, false)))));

        mockMvc.perform(get("/api/crypto-market-indicators").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdtPremium.current").value(0.35))
                .andExpect(jsonPath("$.usdtPremium.history[0].date").value("2026-10-18"))
                .andExpect(jsonPath("$.usdtPremium.history[0].value").value(0.3))
                .andExpect(jsonPath("$.tickers[0].kimchiPremium.current.official").value(1.5))
                .andExpect(jsonPath("$.tickers[0].kimchiPremium.current.tether").value(2.5))
                .andExpect(jsonPath("$.tickers[0].kimchiPremium.history[0].official").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].kimchiPremium.history[0].tether").value(2.4))
                .andExpect(jsonPath("$.tickers[0].fundingRate.current").value(0.0001))
                .andExpect(jsonPath("$.tickers[0].fundingRate.nextFundingTime").exists())
                .andExpect(jsonPath("$.tickers[0].fundingRate.history[0].value").value(0.00012))
                .andExpect(jsonPath("$.tickers[0].valuation.current.date").value("2026-10-18"))
                .andExpect(jsonPath("$.tickers[0].valuation.current.close").value(100000))
                .andExpect(jsonPath("$.tickers[0].valuation.current.wma200").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].valuation.current.wma200Multiple").value(nullValue()))
                .andExpect(jsonPath("$.tickers[0].valuation.current.mayerMultiple").value(1.25))
                .andExpect(jsonPath("$.tickers[0].valuation.history[0].athDrawdownPercent").value(-10.5))
                .andExpect(jsonPath("$.tickers[0].longShortRatio.longAccount").value(0.6331))
                .andExpect(jsonPath("$.tickers[0].longShortRatio.longShortRatio").value(1.7255))
                .andExpect(jsonPath("$.tickers[0].longShortRatio.history[0].date").value("2026-10-18"))
                .andExpect(jsonPath("$.tickers[0].coinbasePremium.current").value(0.0342))
                .andExpect(jsonPath("$.tickers[0].coinbasePremium.history[0].date").value("2026-10-18"))
                .andExpect(jsonPath("$.tickers[0].coinbasePremium.history[0].value").value(-0.0125))
                .andExpect(jsonPath("$.halving.currentHeight").value(972175))
                .andExpect(jsonPath("$.halving.nextHalvingHeight").value(1050000))
                .andExpect(jsonPath("$.halving.blocksRemaining").value(77825))
                .andExpect(jsonPath("$.halving.estimatedAt").value("2028-04-01T00:00:00Z"))
                .andExpect(jsonPath("$.btcDominance.current").value(60.1))
                .andExpect(jsonPath("$.altcoinSeason.history[0].value").value(55))
                .andExpect(jsonPath("$.hashRibbons.current.state").value("CAPITULATION"))
                .andExpect(jsonPath("$.hashRibbons.current.buySignal").value(false))
                .andExpect(jsonPath("$.hashRibbons.current.hashrateEh").value(1050.12));
    }
}
