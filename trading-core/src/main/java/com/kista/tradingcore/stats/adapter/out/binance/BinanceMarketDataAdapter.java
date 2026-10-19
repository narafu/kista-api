package com.kista.tradingcore.stats.adapter.out.binance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.port.output.BinanceMarketDataPort;
import com.kista.tradingcore.stats.domain.model.DatedLongShortRatio;
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
import java.util.ArrayList;
import java.util.List;

// 바이낸스 공개(무인증) 시세 — 현물 api.binance.com, USDT-M 선물 fapi.binance.com
@Component
class BinanceMarketDataAdapter implements BinanceMarketDataPort {

    private static final String SPOT = "https://api.binance.com";
    private static final String FUTURES = "https://fapi.binance.com";
    static final int KLINES_PAGE_LIMIT = 1000; // klines 요청당 최대 봉 수
    static final int KLINES_MAX_PAGES = 10; // 페이징 상한 — 약 27년치, 무한 루프 방지
    static final int FUNDING_PAGE_LIMIT = 1000; // fundingRate 요청당 최대 건수 (8시간 정산 — 약 333일)
    static final int FUNDING_MAX_PAGES = 3; // 페이징 상한 — 약 1,000일, 무한 루프 방지

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
    public List<DatedValue> getSpotDailyClosesSince(StrategyTicker ticker, LocalDate from) {
        List<DatedValue> closes = new ArrayList<>();
        long startTime = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int page = 0; page < KLINES_MAX_PAGES; page++) {
            List<List<Object>> rows = restClient.get()
                    .uri(SPOT + "/api/v3/klines?symbol={s}&interval=1d&startTime={t}&limit={l}", symbol(ticker), startTime, KLINES_PAGE_LIMIT)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            if (rows == null || rows.isEmpty()) return closes;
            rows.stream().map(BinanceMarketDataAdapter::toClose).forEach(closes::add);
            if (rows.size() < KLINES_PAGE_LIMIT) return closes;
            // 다음 페이지는 마지막 봉 openTime 직후부터 — openTime >= startTime 봉만 오므로 겹침·누락 없음
            startTime = openTime(rows.getLast()) + 1;
        }
        // 상한 도달 시 잘라 반환하면 최근 봉이 빠진 채 그럴듯한 값이 나오므로 실패로 처리
        throw new IllegalStateException("바이낸스 일봉 페이징 상한 초과: " + ticker);
    }

    @Override
    public FundingRateQuote getFundingRate(StrategyTicker ticker) {
        PremiumIndexResponse res = restClient.get()
                .uri(FUTURES + "/fapi/v1/premiumIndex?symbol={s}", symbol(ticker))
                .retrieve().body(PremiumIndexResponse.class);
        if (res == null || res.lastFundingRate() == null || res.nextFundingTime() == null) {
            throw new IllegalStateException("바이낸스 펀딩비 응답이 비어있습니다: " + ticker);
        }
        return new FundingRateQuote(res.lastFundingRate(), Instant.ofEpochMilli(res.nextFundingTime()));
    }

    @Override
    public List<FundingRateRecord> getFundingHistory(StrategyTicker ticker, Instant from) {
        List<FundingRateRecord> records = new ArrayList<>();
        long startTime = from.toEpochMilli();
        for (int page = 0; page < FUNDING_MAX_PAGES; page++) {
            List<FundingRateItem> items = restClient.get()
                    .uri(FUTURES + "/fapi/v1/fundingRate?symbol={s}&startTime={t}&limit={l}", symbol(ticker), startTime, FUNDING_PAGE_LIMIT)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            if (items == null || items.isEmpty()) return records;
            items.forEach(i -> records.add(new FundingRateRecord(Instant.ofEpochMilli(i.fundingTime()), i.fundingRate())));
            if (items.size() < FUNDING_PAGE_LIMIT) return records;
            // 다음 페이지는 마지막 정산 직후부터 — fundingTime >= startTime만 오므로 겹침·누락 없음
            startTime = items.getLast().fundingTime() + 1;
        }
        // 상한 도달 시 잘라 반환하면 최근 정산이 빠진 채 그럴듯한 값이 나오므로 실패로 처리
        throw new IllegalStateException("바이낸스 펀딩비 페이징 상한 초과: " + ticker);
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

    @Override
    public List<DatedLongShortRatio> getLongShortAccountRatioHistory(StrategyTicker ticker) {
        List<LongShortRatioItem> items = restClient.get()
                .uri(FUTURES + "/futures/data/globalLongShortAccountRatio?symbol={s}&period=1d&limit=30", symbol(ticker))
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (items == null) return List.of();
        return items.stream()
                .map(i -> new DatedLongShortRatio(utcDate(i.timestamp()), i.longAccount(), i.longShortRatio()))
                .toList();
    }

    // klines 행 구조: [0]=openTime(ms), [4]=종가(문자열)
    private static DatedValue toClose(List<Object> row) {
        return new DatedValue(utcDate(openTime(row)), new BigDecimal((String) row.get(4)));
    }

    private static long openTime(List<Object> row) {
        return ((Number) row.get(0)).longValue();
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
                                @JsonProperty("nextFundingTime") Long nextFundingTime) {}

    record FundingRateItem(@JsonProperty("fundingTime") long fundingTime,
                           @JsonProperty("fundingRate") BigDecimal fundingRate) {}

    record OpenInterestItem(@JsonProperty("timestamp") long timestamp,
                            @JsonProperty("sumOpenInterestValue") BigDecimal sumOpenInterestValue) {}

    record LongShortRatioItem(@JsonProperty("timestamp") long timestamp,
                              @JsonProperty("longAccount") BigDecimal longAccount,
                              @JsonProperty("longShortRatio") BigDecimal longShortRatio) {}
}
