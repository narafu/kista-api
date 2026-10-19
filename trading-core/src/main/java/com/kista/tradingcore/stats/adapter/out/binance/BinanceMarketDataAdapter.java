package com.kista.tradingcore.stats.adapter.out.binance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.port.output.BinanceMarketDataPort;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import com.kista.tradingcore.stats.domain.model.FundingRateQuote;
import com.kista.tradingcore.stats.domain.model.FundingRateRecord;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

// 바이낸스 공개(무인증) 시세 — 현물 api.binance.com, USDT-M 선물 fapi.binance.com
@Component
class BinanceMarketDataAdapter implements BinanceMarketDataPort {

    private static final String SPOT = "https://api.binance.com";
    private static final String FUTURES = "https://fapi.binance.com";

    private final RestClient restClient;

    BinanceMarketDataAdapter(RestClient statsBinanceRestClient) {
        this.restClient = statsBinanceRestClient;
    }

    @Override
    public BigDecimal getSpotPrice(StrategyTicker ticker) {
        PriceResponse res = restClient.get()
                .uri(SPOT + "/api/v3/ticker/price?symbol={s}", symbol(ticker))
                .retrieve().body(PriceResponse.class);
        if (res == null || res.price() == null) {
            throw new IllegalStateException("바이낸스 현물 현재가 응답이 비어있습니다: " + ticker);
        }
        return res.price();
    }

    @Override
    public List<DatedValue> getSpotDailyCloses(StrategyTicker ticker, int limit) {
        List<List<Object>> rows = restClient.get()
                .uri(SPOT + "/api/v3/klines?symbol={s}&interval=1d&limit={l}", symbol(ticker), limit)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (rows == null) return List.of();
        // 행 구조: [0]=openTime(ms), [4]=종가(문자열)
        return rows.stream()
                .map(r -> new DatedValue(utcDate(((Number) r.get(0)).longValue()), new BigDecimal((String) r.get(4))))
                .toList();
    }

    @Override
    public FundingRateQuote getFundingRate(StrategyTicker ticker) {
        PremiumIndexResponse res = restClient.get()
                .uri(FUTURES + "/fapi/v1/premiumIndex?symbol={s}", symbol(ticker))
                .retrieve().body(PremiumIndexResponse.class);
        if (res == null || res.lastFundingRate() == null) {
            throw new IllegalStateException("바이낸스 펀딩비 응답이 비어있습니다: " + ticker);
        }
        return new FundingRateQuote(res.lastFundingRate(), Instant.ofEpochMilli(res.nextFundingTime()));
    }

    @Override
    public List<FundingRateRecord> getFundingHistory(StrategyTicker ticker, Instant from) {
        List<FundingRateItem> items = restClient.get()
                .uri(FUTURES + "/fapi/v1/fundingRate?symbol={s}&startTime={t}&limit=1000",
                        symbol(ticker), from.toEpochMilli())
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (items == null) return List.of();
        return items.stream()
                .map(i -> new FundingRateRecord(Instant.ofEpochMilli(i.fundingTime()), i.fundingRate()))
                .toList();
    }

    @Override
    public List<DatedValue> getOpenInterestHistory(StrategyTicker ticker) {
        List<OpenInterestItem> items = restClient.get()
                .uri(FUTURES + "/futures/data/openInterestHist?symbol={s}&period=1d&limit=30", symbol(ticker))
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (items == null) return List.of();
        return items.stream()
                .map(i -> new DatedValue(utcDate(i.timestamp()), i.sumOpenInterestValue()))
                .toList();
    }

    // 바이낸스 심볼: 티커 + USDT (예: BTCUSDT)
    private static String symbol(StrategyTicker ticker) {
        return ticker.name() + "USDT";
    }

    private static LocalDate utcDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    record PriceResponse(@JsonProperty("price") BigDecimal price) {}

    record PremiumIndexResponse(@JsonProperty("lastFundingRate") BigDecimal lastFundingRate,
                                @JsonProperty("nextFundingTime") long nextFundingTime) {}

    record FundingRateItem(@JsonProperty("fundingTime") long fundingTime,
                           @JsonProperty("fundingRate") BigDecimal fundingRate) {}

    record OpenInterestItem(@JsonProperty("timestamp") long timestamp,
                            @JsonProperty("sumOpenInterestValue") BigDecimal sumOpenInterestValue) {}
}
