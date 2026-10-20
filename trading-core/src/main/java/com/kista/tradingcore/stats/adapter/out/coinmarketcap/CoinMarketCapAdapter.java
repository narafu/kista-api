package com.kista.tradingcore.stats.adapter.out.coinmarketcap;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.stats.application.port.output.CryptoMarketMetricsPort;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

// CoinMarketCap — 도미넌스·알트 시즌 현재값/도미넌스 이력은 공식 Pro API(키 필요), 알트 시즌 이력은 키 없는 비공식 차트 API
@Component
class CoinMarketCapAdapter implements CryptoMarketMetricsPort {

    private static final String OFFICIAL_URL = "https://pro-api.coinmarketcap.com";
    private static final String CHART_URL = "https://api.coinmarketcap.com/data-api/v3/altcoin-season/chart";
    private static final String KEY_HEADER = "X-CMC_PRO_API_KEY"; // CMC 인증 헤더

    private final RestClient restClient;
    private final String apiKey;

    CoinMarketCapAdapter(RestClient statsCoinMarketCapRestClient, CoinMarketCapProperties properties) {
        this.restClient = statsCoinMarketCapRestClient;
        this.apiKey = properties.apiKey();
    }

    @Override
    public BigDecimal btcDominance() {
        return official("/v1/global-metrics/quotes/latest", GlobalLatest.class, GlobalLatest::data, GlobalLatest::status)
                .btcDominance();
    }

    @Override
    public List<DatedValue> btcDominanceDaily(int count) {
        HistoricalData data = official("/v1/global-metrics/quotes/historical?interval=daily&count=" + count,
                Historical.class, Historical::data, Historical::status);
        TreeMap<LocalDate, BigDecimal> byDate = new TreeMap<>(); // 날짜 중복 시 마지막 점이 이김
        for (Quote q : data.quotes()) {
            byDate.put(Instant.parse(q.timestamp()).atZone(ZoneOffset.UTC).toLocalDate(), q.btcDominance());
        }
        return toList(byDate);
    }

    @Override
    public BigDecimal altcoinSeasonIndex() {
        return official("/v1/altcoin-season-index/latest", AltLatest.class, AltLatest::data, AltLatest::status)
                .altcoinIndex();
    }

    @Override
    public List<DatedValue> altcoinSeasonDaily(LocalDate from, LocalDate to) {
        // 키 없이 호출하는 비공식 API — end는 to 다음날 UTC 자정(배타)
        long start = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Chart res = restClient.get().uri(CHART_URL + "?start=" + start + "&end=" + end).retrieve().body(Chart.class);
        if (res == null || res.data() == null || res.data().points() == null) {
            throw new IllegalStateException("CoinMarketCap 알트 시즌 차트 응답이 비어있습니다");
        }
        TreeMap<LocalDate, BigDecimal> byDate = new TreeMap<>();
        // points는 시간순 — 같은 날짜는 뒤 점이 덮어써 마지막 점이 남는다. 값이 문자열로 온다
        for (ChartPoint p : res.data().points()) {
            byDate.put(Instant.ofEpochSecond(Long.parseLong(p.timestamp())).atZone(ZoneOffset.UTC).toLocalDate(),
                    new BigDecimal(p.altcoinIndex()));
        }
        return toList(byDate);
    }

    // 공식 API 공통 호출 — 키 확인 → 헤더 → status.error_code 검사 → data null 검사
    private <R, D> D official(String path, Class<R> type, Function<R, D> data, Function<R, Status> status) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("CMC_API_KEY가 설정되지 않았습니다");
        R res = restClient.get().uri(OFFICIAL_URL + path).header(KEY_HEADER, apiKey).retrieve().body(type);
        Status st = res == null ? null : status.apply(res);
        if (st != null && st.errorCode() != 0) {
            throw new IllegalStateException("CoinMarketCap 오류 " + st.errorCode() + ": " + st.errorMessage());
        }
        D d = res == null ? null : data.apply(res);
        if (d == null) throw new IllegalStateException("CoinMarketCap 응답에 data가 없습니다: " + path);
        return d;
    }

    private static List<DatedValue> toList(TreeMap<LocalDate, BigDecimal> byDate) {
        return byDate.entrySet().stream().map(e -> new DatedValue(e.getKey(), e.getValue())).toList();
    }

    record Status(@JsonProperty("error_code") int errorCode, @JsonProperty("error_message") String errorMessage) {}

    record GlobalLatest(@JsonProperty("status") Status status, @JsonProperty("data") GlobalData data) {}

    record GlobalData(@JsonProperty("btc_dominance") BigDecimal btcDominance) {}

    record Historical(@JsonProperty("status") Status status, @JsonProperty("data") HistoricalData data) {}

    record HistoricalData(@JsonProperty("quotes") List<Quote> quotes) {}

    record Quote(@JsonProperty("timestamp") String timestamp, @JsonProperty("btc_dominance") BigDecimal btcDominance) {}

    record AltLatest(@JsonProperty("status") Status status, @JsonProperty("data") AltData data) {}

    record AltData(@JsonProperty("altcoin_index") BigDecimal altcoinIndex) {}

    record Chart(@JsonProperty("data") ChartData data) {}

    record ChartData(@JsonProperty("points") List<ChartPoint> points) {}

    record ChartPoint(@JsonProperty("altcoinIndex") String altcoinIndex, @JsonProperty("timestamp") String timestamp) {}
}
