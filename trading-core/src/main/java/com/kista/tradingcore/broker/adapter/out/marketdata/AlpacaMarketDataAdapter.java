package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.alpaca.AlpacaProperties;
import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

// 계좌와 무관한 공용 시세(Alpaca) — 화면의 현재가와 확정된 값(전일 종가·확정 일봉·일봉 이력)의 단일 출처다.
// 주문 경로의 현재가는 여기가 아니라 계좌 증권사에서 읽는다(decisions.md "공용 시세는 Alpaca").
// 구현이 하나라 포트 인터페이스 없이 이 클래스를 직접 주입한다(가드 interfaces_must_earn_their_keep). 시그니처는 도메인 타입만 쓴다.
// 계약: 값이 없는 종목은 결과 맵에 키가 없다. 다른 출처·현재가로 대체하지 않는다. 날짜 인자는 미국 세션일이고 변환은 호출하는 쪽이 한다
@Slf4j
@Component
@NamedInterface("marketdata")
public class AlpacaMarketDataAdapter {

    private static final Duration LATEST_PRICE_TTL = Duration.ofSeconds(60); // 현재가 캐시
    private static final Duration SESSION_VALUE_TTL = Duration.ofHours(24);  // 세션일이 키에 든 확정 값 — 그날 내내
    private static final Duration CANDLES_TTL = Duration.ofHours(1);         // 일봉 이력 캐시
    private static final LocalTime BAR_SETTLED_AT = LocalTime.of(16, 16);    // 뉴욕 시각 — 이 뒤에 읽은 당일 봉은 완결된 것으로 본다
    private static final int PREV_CLOSE_LOOKBACK_DAYS = 10;                  // 전일 종가를 찾는 달력일 범위(연휴 포함)
    private static final int PAGE_LIMIT = 10_000;                            // Alpaca bars 한 페이지 최대 건수(종목 합산)
    private static final String FEED_IEX = "iex";                            // 실시간, 한 거래소
    private static final String FEED_DELAYED_SIP = "delayed_sip";            // 15분 지연 통합 시세(snapshot 전용)
    private static final String FEED_SIP = "sip";                            // 통합 시세 일봉

    private final RestClient marketDataAlpacaRestClient; // 시세 API baseUrl 고정
    private final AlpacaProperties alpacaProperties;     // 인증 헤더 값
    private final Clock clock;                           // 오늘 세션 판정·캐시 만료
    private final TtlResultCache cache;                  // 종목별 인메모리 캐시(시세를 읽는 프로세스는 하나)

    AlpacaMarketDataAdapter(RestClient marketDataAlpacaRestClient, AlpacaProperties alpacaProperties, Clock clock) {
        this.marketDataAlpacaRestClient = marketDataAlpacaRestClient;
        this.alpacaProperties = alpacaProperties;
        this.clock = clock;
        this.cache = new TtlResultCache(clock);
    }

    // 근사 현재가 — iex와 지연 통합 시세 중 체결 시각이 더 늦은 쪽. 화면·Mock 계좌용
    public Map<StrategyTicker, BigDecimal> latestPrices(Collection<StrategyTicker> tickers) {
        return cachedPerTicker("latest", "", tickers, LATEST_PRICE_TTL, this::loadLatestPrices);
    }

    // usSessionDate 세션의 확정 일봉(원주가 OHLC). 마감+16분 전에는 진행 중인 봉이 오므로 호출하는 쪽이 마감 뒤에 부른다
    public Map<StrategyTicker, BrokerCandle> confirmedBars(Collection<StrategyTicker> tickers, LocalDate usSessionDate) {
        // 완결 전에 읽은 봉이 그날 내내 남지 않게, 정규 마감 16:00 + 통합 시세 지연 15분이 지난 뒤에 읽은 값만 캐시한다
        // ponytail: 조기 마감일(13:00)은 16:16까지 캐시만 안 된다. 완결 전 호출을 거부하려면 세션 closeAt을 인자로 받는다(N19)
        boolean settled = !clock.instant().isBefore(usSessionDate.atTime(BAR_SETTLED_AT).atZone(TimeZones.NY).toInstant());
        return cachedPerTicker("bar", usSessionDate, tickers, settled ? SESSION_VALUE_TTL : Duration.ZERO, missing -> {
            // 최근 세션에 end를 주면 무료 키는 403이다 — end 없이 받아 봉 날짜로 고른다
            Map<String, List<Bar>> bars = fetchBars(symbols(missing), usSessionDate, null, DailyCandleQuery.Adjustment.RAW, false, null);
            return pickPerTicker(missing, bars, list -> list.stream()
                    .filter(bar -> bar.date().equals(usSessionDate)).findFirst().map(Bar::toCandle).orElse(null));
        });
    }

    // usSessionDate 세션 직전 확정 종가(분할 조정)
    public Map<StrategyTicker, BigDecimal> closesBefore(Collection<StrategyTicker> tickers, LocalDate usSessionDate) {
        return cachedPerTicker("prevClose", usSessionDate, tickers, SESSION_VALUE_TTL, missing -> {
            Map<String, List<Bar>> bars = fetchBars(symbols(missing), usSessionDate.minusDays(PREV_CLOSE_LOOKBACK_DAYS), null,
                    DailyCandleQuery.Adjustment.SPLIT, false, null);
            // 응답에 세션일 봉(진행 중)이 섞여 와도 세션일보다 앞인 마지막 봉만 쓴다
            return pickPerTicker(missing, bars, list -> list.stream()
                    .filter(bar -> bar.date().isBefore(usSessionDate)).max(Comparator.comparing(Bar::date)).map(Bar::c).orElse(null));
        });
    }

    // 일봉 이력(오름차순) — 차트·DCA 지표·백테스트. symbol은 전략 종목이 아닌 것(지수 ETF)도 받는다
    public List<BrokerCandle> dailyCandles(String symbol, DailyCandleQuery query) {
        List<BrokerCandle> cached = cache.getOrCompute(new CacheKey("candles", query, symbol), CANDLES_TTL, () -> {
            List<BrokerCandle> loaded = loadDailyCandles(symbol, query);
            return loaded.isEmpty() ? null : loaded; // 빈 결과는 캐시하지 않는다
        });
        return cached != null ? cached : List.of();
    }

    private Map<StrategyTicker, BigDecimal> loadLatestPrices(List<StrategyTicker> tickers) {
        String symbols = symbols(tickers);
        RestClientException failure = null;
        Map<String, Trade> latest = new HashMap<>();
        for (String feed : List.of(FEED_IEX, FEED_DELAYED_SIP)) {
            try {
                fetchSnapshots(symbols, feed).forEach((symbol, snapshot) -> {
                    if (snapshot != null && snapshot.latestTrade() != null && snapshot.latestTrade().t() != null && snapshot.latestTrade().p() != null) {
                        // 거래가 적은 종목은 iex 최근 체결이 며칠 전일 수 있다 — 더 늦은 체결을 고른다
                        latest.merge(symbol, snapshot.latestTrade(), (a, b) -> b.at().isAfter(a.at()) ? b : a);
                    }
                });
            } catch (RestClientException e) {
                // 한쪽 피드 실패는 다른 쪽만으로 답한다 — 거래 적은 종목의 값이 묵을 수 있어 경고로 남긴다
                log.warn("[시세] Alpaca 현재가 피드 {} 실패, 남은 피드로 답합니다: {}", feed, e.getMessage());
                if (failure != null) {
                    e.addSuppressed(failure);
                }
                failure = e;
            }
        }
        if (latest.isEmpty() && failure != null) {
            throw failure;
        }
        return pickPerTicker(tickers, latest, Trade::p);
    }

    private List<BrokerCandle> loadDailyCandles(String symbol, DailyCandleQuery query) {
        LocalDate today = LocalDate.now(clock.withZone(TimeZones.NY));
        // 오늘 이후가 끝이면 end를 뺀다(최근 15분에 걸리면 403)
        LocalDate end = query.to() != null && query.to().isBefore(today) ? query.to() : null;
        if (query.count() == null) {
            return fetchBars(symbol, query.from(), end, query.adjustment(), false, null).getOrDefault(symbol, List.of()).stream()
                    .filter(bar -> query.to() == null || !bar.date().isAfter(query.to()))
                    .map(Bar::toCandle).toList();
        }
        // 최근 N개: 내림차순으로 N개만 받아 뒤집는다. 시작일은 거래일이 달력일의 절반을 넘는다는 여유로 잡는다
        LocalDate start = (end != null ? end : today).minusDays(query.count() * 2L + 30);
        // to 지정(확정 봉 계약)인데 end를 뺀 경우 응답에 진행 중인 오늘 봉이 섞일 수 있다 — 마감+16분 전이면 버리고, 한 개 빠져도 count개가 차도록 limit을 하나 더 받는다
        boolean dropOpenBar = query.to() != null && end == null
                && clock.instant().isBefore(today.atTime(BAR_SETTLED_AT).atZone(TimeZones.NY).toInstant());
        return fetchBars(symbol, start, end, query.adjustment(), true, query.count() + (dropOpenBar ? 1 : 0)).getOrDefault(symbol, List.of()).stream()
                .filter(bar -> query.to() == null || !bar.date().isAfter(query.to()))
                .filter(bar -> !dropOpenBar || !bar.date().equals(today))
                .limit(query.count())
                .sorted(Comparator.comparing(Bar::date))
                .map(Bar::toCandle).toList();
    }

    // GET /v2/stocks/snapshots — 응답은 종목을 키로 한 맵
    private Map<String, Snapshot> fetchSnapshots(String symbols, String feed) {
        Map<String, Snapshot> body = withOneRetry(() -> marketDataAlpacaRestClient.get()
                .uri(uri -> uri.path("/v2/stocks/snapshots").queryParam("symbols", symbols).queryParam("feed", feed).build())
                .header("APCA-API-KEY-ID", alpacaProperties.apiKey())
                .header("APCA-API-SECRET-KEY", alpacaProperties.apiSecret())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Snapshot>>() {}));
        return body != null ? body : Map.of();
    }

    // GET /v2/stocks/bars — limit은 종목 합산이고 종목순·시각순으로 온다. next_page_token이 없을 때까지(또는 maxBars를 채울 때까지) 따라간다
    private Map<String, List<Bar>> fetchBars(String symbols, LocalDate start, @Nullable LocalDate end,
                                             DailyCandleQuery.Adjustment adjustment, boolean descending, @Nullable Integer maxBars) {
        Map<String, List<Bar>> result = new HashMap<>();
        int total = 0;
        String pageToken = null;
        do {
            String token = pageToken;
            BarsResponse page = withOneRetry(() -> marketDataAlpacaRestClient.get()
                    .uri(uri -> {
                        uri.path("/v2/stocks/bars")
                                .queryParam("symbols", symbols)
                                .queryParam("timeframe", "1Day")
                                .queryParam("feed", FEED_SIP) // iex 일봉 종가는 통합 종가와 다르다
                                .queryParam("adjustment", adjustment.name().toLowerCase(Locale.ROOT))
                                .queryParam("start", start)
                                .queryParam("sort", descending ? "desc" : "asc")
                                .queryParam("limit", maxBars != null ? Math.min(maxBars, PAGE_LIMIT) : PAGE_LIMIT);
                        if (end != null) {
                            uri.queryParam("end", end);
                        }
                        if (token == null) {
                            return uri.build();
                        }
                        // 토큰은 URI 변수로 넘겨 '+' 같은 문자까지 인코딩한다
                        return uri.queryParam("page_token", "{token}").build(token);
                    })
                    .header("APCA-API-KEY-ID", alpacaProperties.apiKey())
                    .header("APCA-API-SECRET-KEY", alpacaProperties.apiSecret())
                    .retrieve()
                    .body(BarsResponse.class));
            if (page == null || page.bars() == null) {
                break;
            }
            for (Map.Entry<String, List<Bar>> entry : page.bars().entrySet()) {
                result.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
                total += entry.getValue().size();
            }
            // 같은 토큰이 되돌아오면 끝없이 돌지 않게 멈춘다
            pageToken = page.nextPageToken() != null && !page.nextPageToken().equals(token) ? page.nextPageToken() : null;
        } while (pageToken != null && (maxBars == null || total < maxBars));
        return result;
    }

    // 연결 실패·5xx만 한 번 다시 부른다 — 4xx(권한·인자)는 다시 불러도 같다
    private static <T> T withOneRetry(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException | HttpServerErrorException e) {
            return call.get();
        }
    }

    // 캐시에 있는 종목은 그대로, 없는 종목만 한 번에 조회한다. null(값 없음·실패)은 저장하지 않는다
    private <V> Map<StrategyTicker, V> cachedPerTicker(String kind, Object scope, Collection<StrategyTicker> tickers, Duration ttl,
                                                       Function<List<StrategyTicker>, Map<StrategyTicker, V>> loader) {
        Map<StrategyTicker, V> result = new EnumMap<>(StrategyTicker.class);
        List<StrategyTicker> missing = new ArrayList<>();
        for (StrategyTicker ticker : tickers.stream().distinct().toList()) {
            V hit = cache.peek(new CacheKey(kind, scope, ticker));
            if (hit != null) {
                result.put(ticker, hit);
            } else {
                missing.add(ticker);
            }
        }
        if (!missing.isEmpty()) {
            loader.apply(missing).forEach((ticker, value) ->
                    result.put(ticker, cache.getOrCompute(new CacheKey(kind, scope, ticker), ttl, () -> value)));
        }
        return result;
    }

    private static <S, V> Map<StrategyTicker, V> pickPerTicker(List<StrategyTicker> tickers, Map<String, S> bySymbol, Function<S, V> pick) {
        Map<StrategyTicker, V> result = new EnumMap<>(StrategyTicker.class);
        for (StrategyTicker ticker : tickers) {
            S source = bySymbol.get(ticker.name());
            V value = source != null ? pick.apply(source) : null;
            if (value != null) {
                result.put(ticker, value);
            }
        }
        return result;
    }

    private static String symbols(Collection<StrategyTicker> tickers) {
        return String.join(",", tickers.stream().map(StrategyTicker::name).toList());
    }

    private record CacheKey(String kind, Object scope, Object subject) {}

    record Trade(String t, BigDecimal p) {
        Instant at() {
            return Instant.parse(t); // RFC-3339 나노초
        }
    }

    record Snapshot(Trade latestTrade) {}

    record Bar(String t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, long v) {
        // 일봉 시각은 뉴욕 자정이다 — 미국 세션일로 읽는다
        LocalDate date() {
            return Instant.parse(t).atZone(TimeZones.NY).toLocalDate();
        }

        BrokerCandle toCandle() {
            return new BrokerCandle(date(), o, h, l, c, v);
        }
    }

    record BarsResponse(Map<String, List<Bar>> bars, @JsonProperty("next_page_token") String nextPageToken) {}
}
