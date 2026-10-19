package com.kista.tradingcore.stats.adapter.out.binance;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import com.kista.tradingcore.stats.domain.model.FundingRateQuote;
import com.kista.tradingcore.stats.domain.model.FundingRateRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceMarketDataAdapterTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BinanceMarketDataAdapter adapter = new BinanceMarketDataAdapter(builder.build());

    @Test
    void 현물_현재가를_USDT_심볼로_조회한다() {
        server.expect(requestTo("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"symbol\":\"BTCUSDT\",\"price\":\"83034.01\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.getSpotPrice(StrategyTicker.BTC)).isEqualByComparingTo("83034.01");
        server.verify();
    }

    @Test
    void 일봉은_openTime_UTC_날짜와_종가로_매핑한다() {
        server.expect(requestTo("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=2"))
                .andRespond(withSuccess("""
                        [[1792368000000,"82116.0","83158.0","82010.58","83034.01","6092.44",1792454399999,"1","1050886","1","1","0"]]
                        """, MediaType.APPLICATION_JSON));

        List<DatedValue> result = adapter.getSpotDailyCloses(StrategyTicker.BTC, 2);

        assertThat(result).containsExactly(new DatedValue(LocalDate.of(2026, 10, 19), new BigDecimal("83034.01")));
    }

    @Test
    void 펀딩비_현재값과_다음_정산시각을_매핑한다() {
        server.expect(requestTo("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=BTCUSDT"))
                .andRespond(withSuccess("""
                        {"symbol":"BTCUSDT","markPrice":"83008.9","lastFundingRate":"0.00010000","nextFundingTime":1792425600000,"time":1792402827000}
                        """, MediaType.APPLICATION_JSON));

        FundingRateQuote quote = adapter.getFundingRate(StrategyTicker.BTC);

        assertThat(quote.rate()).isEqualByComparingTo("0.0001");
        assertThat(quote.nextFundingTime()).isEqualTo(Instant.ofEpochMilli(1792425600000L));
    }

    @Test
    void 펀딩비_이력은_startTime_ms와_limit_1000으로_조회한다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://fapi.binance.com/fapi/v1/fundingRate")))
                .andExpect(queryParam("symbol", "BTCUSDT"))
                .andExpect(queryParam("startTime", "1790000000000"))
                .andExpect(queryParam("limit", "1000"))
                .andRespond(withSuccess("""
                        [{"symbol":"BTCUSDT","fundingTime":1792368000000,"fundingRate":"0.00004958","markPrice":"82086.07"}]
                        """, MediaType.APPLICATION_JSON));

        List<FundingRateRecord> result = adapter.getFundingHistory(StrategyTicker.BTC, Instant.ofEpochMilli(1790000000000L));

        assertThat(result).containsExactly(
                new FundingRateRecord(Instant.ofEpochMilli(1792368000000L), new BigDecimal("0.00004958")));
    }

    @Test
    void OI_이력은_period_1d_limit_30으로_조회해_USD_명목가로_매핑한다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://fapi.binance.com/futures/data/openInterestHist")))
                .andExpect(queryParam("symbol", "BTCUSDT"))
                .andExpect(queryParam("period", "1d"))
                .andExpect(queryParam("limit", "30"))
                .andRespond(withSuccess("""
                        [{"symbol":"BTCUSDT","sumOpenInterest":"93130.501","sumOpenInterestValue":"7644724174.10721700","timestamp":1792368000000}]
                        """, MediaType.APPLICATION_JSON));

        List<DatedValue> result = adapter.getOpenInterestHistory(StrategyTicker.BTC);

        assertThat(result).containsExactly(
                new DatedValue(LocalDate.of(2026, 10, 19), new BigDecimal("7644724174.10721700")));
    }
}
