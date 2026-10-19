package com.kista.tradingcore.stats.adapter.out.coinbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.stats.application.port.output.CoinbaseMarketDataPort;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeMap;

// 코인베이스 Exchange 공개(무인증) 시세 — api.exchange.coinbase.com
@Component
class CoinbaseMarketDataAdapter implements CoinbaseMarketDataPort {

    private static final String BASE_URL = "https://api.exchange.coinbase.com";
    private static final int ONE_DAY_SECONDS = 86_400; // 일봉 granularity
    private static final int MAX_CANDLES = 300; // 요청당 최대 봉 수
    private static final int MAX_PAGES = 3; // 페이징 상한 — 약 900일, 무한 루프 방지

    private final RestClient restClient;

    CoinbaseMarketDataAdapter(RestClient statsCoinbaseRestClient) {
        this.restClient = statsCoinbaseRestClient;
    }

    @Override
    public BigDecimal getUsdPrice(String asset) {
        TickerResponse res = restClient.get()
                .uri(BASE_URL + "/products/{p}/ticker", product(asset))
                .retrieve().body(TickerResponse.class);
        if (res == null || res.price() == null) {
            throw new IllegalStateException("코인베이스 현재가 응답이 비어있습니다: " + asset);
        }
        return res.price();
    }

    // 첫 페이지는 start/end 없이 최신 300봉, from까지 모자라면 더 오래된 300일 창을 뒤로 이어 받는다
    @Override
    public List<DatedValue> getUsdDailyClosesSince(String asset, LocalDate from) {
        TreeMap<LocalDate, BigDecimal> closes = new TreeMap<>(); // 날짜 중복 제거 + 오래된 순
        String uri = BASE_URL + "/products/{p}/candles?granularity={g}";
        Object[] vars = {product(asset), ONE_DAY_SECONDS};
        for (int page = 0; page < MAX_PAGES; page++) {
            List<List<BigDecimal>> rows = restClient.get().uri(uri, vars)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            if (rows == null || rows.isEmpty()) break; // 상장 이력 소진
            // 행 구조: [0]=봉 시작(UTC epoch초), [1]=저가, [2]=고가, [3]=시가, [4]=종가, [5]=거래량 — 최신 봉이 먼저 온다
            rows.forEach(r -> closes.put(Instant.ofEpochSecond(r.get(0).longValue()).atZone(ZoneOffset.UTC).toLocalDate(), r.get(4)));
            LocalDate end = closes.firstKey().minusDays(1);
            if (end.isBefore(from)) return toDatedValues(closes, from);
            // start·end 모두 포함 — 299일 차이 창이 정확히 300봉
            LocalDate start = end.minusDays(MAX_CANDLES - 1);
            if (start.isBefore(from)) start = from;
            uri = BASE_URL + "/products/{p}/candles?granularity={g}&start={s}&end={e}";
            vars = new Object[]{product(asset), ONE_DAY_SECONDS, start.atStartOfDay(ZoneOffset.UTC).toInstant(), end.atStartOfDay(ZoneOffset.UTC).toInstant()};
            if (page == MAX_PAGES - 1) throw new IllegalStateException("코인베이스 일봉 페이징 상한 초과: " + asset);
        }
        return toDatedValues(closes, from);
    }

    private static List<DatedValue> toDatedValues(TreeMap<LocalDate, BigDecimal> closes, LocalDate from) {
        return closes.tailMap(from).entrySet().stream().map(e -> new DatedValue(e.getKey(), e.getValue())).toList();
    }

    // 코인베이스 상품 ID: 자산 + -USD (예: BTC-USD)
    private static String product(String asset) {
        return asset + "-USD";
    }

    record TickerResponse(@JsonProperty("price") BigDecimal price) {}
}
