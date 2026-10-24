package com.kista.tradingcore.stats.adapter.out.binance;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.domain.model.DatedLongShortRatio;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import com.kista.tradingcore.stats.domain.model.FundingRateQuote;
import com.kista.tradingcore.stats.domain.model.FundingRateRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceMarketDataAdapterTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BinanceMarketDataAdapter adapter = new BinanceMarketDataAdapter(builder.build());

    @AfterEach
    void verifyServer() {
        server.verify(); // 기대한 요청이 모두 나갔는지 — 모든 테스트 공통
    }

    @Test
    void 현물_현재가를_USDT_심볼로_조회한다() {
        server.expect(requestTo("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"symbol\":\"BTCUSDT\",\"price\":\"83034.01\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.getSpotPrice(StrategyTicker.BTC)).isEqualByComparingTo("83034.01");
        server.verify();
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

    private static final long DAY_MS = 86_400_000L;
    private static final long LISTING = 1502928000000L; // 2017-08-17 BTCUSDT 첫 봉 (실측)

    // openTime(ms)부터 하루 간격 n개 일봉 행 JSON — 종가는 closeOffset부터 1씩 증가
    private static String klines(long firstOpenTime, int n, int closeOffset) {
        return LongStream.range(0, n)
                .mapToObj(i -> "[%d,\"1\",\"1\",\"1\",\"%d\",\"1\",%d,\"1\",1,\"1\",\"1\",\"0\"]"
                        .formatted(firstOpenTime + i * DAY_MS, closeOffset + i, firstOpenTime + (i + 1) * DAY_MS - 1))
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void 장기_일봉은_1000개씩_마지막_openTime_직후로_페이징해_겹침_누락_없이_잇는다() {
        long from = 1483228800000L; // 2017-01-01 UTC — 상장 전 startTime도 첫 봉부터 응답(실측)
        long secondStart = LISTING + 999 * DAY_MS + 1;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.binance.com/api/v3/klines")))
                .andExpect(queryParam("symbol", "BTCUSDT"))
                .andExpect(queryParam("interval", "1d"))
                .andExpect(queryParam("startTime", String.valueOf(from)))
                .andExpect(queryParam("limit", "1000"))
                .andRespond(withSuccess(klines(LISTING, 1000, 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.binance.com/api/v3/klines")))
                .andExpect(queryParam("startTime", String.valueOf(secondStart)))
                .andRespond(withSuccess(klines(LISTING + 1000 * DAY_MS, 3, 1001), MediaType.APPLICATION_JSON));

        List<DatedValue> result = adapter.getSpotDailyClosesSince(StrategyTicker.BTC, LocalDate.of(2017, 1, 1));

        server.verify();
        assertThat(result).hasSize(1003);
        // openTime의 UTC 날짜와 [4] 종가로 매핑
        assertThat(result.getFirst()).isEqualTo(new DatedValue(LocalDate.of(2017, 8, 17), new BigDecimal("1")));
        // 연속된 날짜 — 겹침·누락 없음
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).date()).isEqualTo(result.get(i - 1).date().plusDays(1));
            assertThat(result.get(i).value()).isEqualByComparingTo(BigDecimal.valueOf(i + 1));
        }
    }

    @Test
    void 장기_일봉이_정확히_1000개로_끝나면_빈_다음_페이지에서_멈춘다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.binance.com/api/v3/klines")))
                .andRespond(withSuccess(klines(LISTING, 1000, 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.binance.com/api/v3/klines")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(adapter.getSpotDailyClosesSince(StrategyTicker.BTC, LocalDate.of(2017, 1, 1))).hasSize(1000);
        server.verify();
    }

    @Test
    void 장기_일봉_페이징_상한을_넘으면_잘라_반환하지_않고_실패한다() {
        for (int p = 0; p < BinanceMarketDataAdapter.KLINES_MAX_PAGES; p++) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.binance.com/api/v3/klines")))
                    .andRespond(withSuccess(klines(LISTING + p * 1000L * DAY_MS, 1000, 1), MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> adapter.getSpotDailyClosesSince(StrategyTicker.BTC, LocalDate.of(2017, 1, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final long EIGHT_HOURS_MS = 28_800_000L;

    // fundingTime(ms)부터 8시간 간격 n건 펀딩비 JSON
    private static String fundings(long firstTime, int n) {
        return LongStream.range(0, n)
                .mapToObj(i -> "{\"symbol\":\"BTCUSDT\",\"fundingTime\":%d,\"fundingRate\":\"0.0001\"}".formatted(firstTime + i * EIGHT_HOURS_MS))
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void 펀딩비_이력이_1000건이면_마지막_fundingTime_직후로_이어_받는다() {
        long first = 1790000000000L;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://fapi.binance.com/fapi/v1/fundingRate")))
                .andExpect(queryParam("startTime", String.valueOf(first)))
                .andRespond(withSuccess(fundings(first, 1000), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://fapi.binance.com/fapi/v1/fundingRate")))
                .andExpect(queryParam("startTime", String.valueOf(first + 999 * EIGHT_HOURS_MS + 1)))
                .andExpect(queryParam("limit", "1000"))
                .andRespond(withSuccess(fundings(first + 1000 * EIGHT_HOURS_MS, 200), MediaType.APPLICATION_JSON));

        List<FundingRateRecord> result = adapter.getFundingHistory(StrategyTicker.BTC, Instant.ofEpochMilli(first));

        server.verify();
        assertThat(result).hasSize(1200);
        assertThat(result.getLast().fundingTime()).isEqualTo(Instant.ofEpochMilli(first + 1199 * EIGHT_HOURS_MS));
    }

    @Test
    void 펀딩비_페이징_상한을_넘으면_잘라_반환하지_않고_실패한다() {
        for (int p = 0; p < BinanceMarketDataAdapter.FUNDING_MAX_PAGES; p++) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://fapi.binance.com/fapi/v1/fundingRate")))
                    .andRespond(withSuccess(fundings(1790000000000L + p * 1000L * EIGHT_HOURS_MS, 1000), MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> adapter.getFundingHistory(StrategyTicker.BTC, Instant.ofEpochMilli(1790000000000L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 롱숏_계좌_비율은_period_1d_limit_30으로_조회해_UTC_날짜로_매핑한다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://fapi.binance.com/futures/data/globalLongShortAccountRatio")))
                .andExpect(queryParam("symbol", "BTCUSDT"))
                .andExpect(queryParam("period", "1d"))
                .andExpect(queryParam("limit", "30"))
                .andRespond(withSuccess("""
                        [{"symbol":"BTCUSDT","longAccount":"0.6384","longShortRatio":"1.7655","shortAccount":"0.3616","timestamp":1792368000000}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.getLongShortAccountRatioHistory(StrategyTicker.BTC)).containsExactly(
                new DatedLongShortRatio(LocalDate.of(2026, 10, 19), new BigDecimal("0.6384"), new BigDecimal("1.7655")));
    }
}
