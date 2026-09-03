package com.kista.broker.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.broker.adapter.out.internal.PrevCloseCache;
import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.common.TimeZones;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.TossCandle;
import com.kista.broker.domain.model.toss.TossStockInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
class TossPriceApi implements CommonMarketPriceFeed {

    // Toss 가격 API: GET /api/v1/prices?symbols=SOXL,TQQQ (콤마 구분, 최대 200개)
    private static final String PRICES_PATH = "/api/v1/prices";
    // Toss 종목 기본 정보 API: GET /api/v1/stocks?symbols=SOXL (복수형, 콤마 구분)
    // 주의: stocks API는 가격 정보 미제공 — 현재가는 /prices 별도 조회
    private static final String STOCKS_PATH = "/api/v1/stocks";

    private final TossHttpClient tossHttpClient;
    private final TossCandleApi tossCandleApi; // 전일종가 캔들 조회
    private final PrevCloseCache prevCloseCache = new PrevCloseCache();
    private final TossStockInfoCache stockInfoCache = new TossStockInfoCache(Duration.ofHours(6), Instant::now);

    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();

        // symbols 쿼리 파라미터: 콤마 구분 종목 코드 목록
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbols", tickers.stream().map(StrategyTicker::name).collect(Collectors.joining(",")));

        // 공통 API — 관리자 토큰 사용
        TossResult<List<PriceItem>> wrapper = tossHttpClient.getCommon(PRICES_PATH, params,
                new ParameterizedTypeReference<TossResult<List<PriceItem>>>() {});
        List<PriceItem> items = wrapper != null ? wrapper.result() : null;

        if (items == null) return Map.of();

        return items.stream()
                .flatMap(item -> StrategyTicker.tryParse(item.symbol())  // StrategyTicker 외 종목(예: AAPL) silent drop
                        .map(t -> Map.entry(t, new BigDecimal(item.lastPrice())))
                        .stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public BigDecimal getPrice(StrategyTicker ticker) {
        // 단건도 getPrices 재사용 — HTTP 호출 횟수 동일
        return getPrices(List.of(ticker)).getOrDefault(ticker, BigDecimal.ZERO);
    }

    public PriceSnapshot getPriceSnapshot(StrategyTicker ticker) {
        BigDecimal price = getPrice(ticker);
        return new PriceSnapshot(price, fetchPrevCloseCached(ticker.name()).orElse(price));
    }

    public Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers) {
        return getPrices(tickers).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new PriceSnapshot(e.getValue(), fetchPrevCloseCached(e.getKey().name()).orElse(e.getValue()))));
    }

    // 전일종가만 필요한 경우 — 현재가 API(/api/v1/prices) 미호출, 캔들 API만 호출
    // 캔들 조회가 실패한 종목만 현재가로 fallback (드문 경우라 별도 배치 호출로 보충)
    public BigDecimal getPrevClose(StrategyTicker ticker) {
        return getPrevCloses(List.of(ticker)).getOrDefault(ticker, BigDecimal.ZERO);
    }

    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();

        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        List<StrategyTicker> needsFallback = new ArrayList<>();
        for (StrategyTicker ticker : tickers) {
            fetchPrevCloseCached(ticker.name())
                    .ifPresentOrElse(
                            prevClose -> result.put(ticker, prevClose),
                            () -> needsFallback.add(ticker));
        }
        if (!needsFallback.isEmpty()) {
            log.warn("전일종가 캔들 조회 실패 종목 — 현재가로 fallback: tickers={}", needsFallback);
            result.putAll(getPrices(needsFallback));
        }
        return result;
    }

    // count=1 + before로 확정 종가 캔들 1개만 조회 — 정규장 진행 중이면 진행 중인 봉을 배제하기 위해
    // before를 가장 최근 개장 시각 직전으로, 그 외(프리마켓·장마감 후)는 지금 시각으로 잡음
    // 같은 (symbol, KST 날짜, 정규장 진행 여부) 재조회는 캐시 히트 — 정규장 진행 여부를 버킷으로 분리해
    // 정규장 종료로 확정 종가가 바뀌는 순간에는 캐시를 재사용하지 않고 새로 조회하도록 함
    // 실패(empty)도 캐싱되어 같은 버킷 내 재시도하지 않음(허용된 트레이드오프)
    private Optional<BigDecimal> fetchPrevCloseCached(String symbol) {
        MarketSessionInfo session = resolveMarketSession();
        Instant before = session.regularSessionActive()
                ? session.lastSessionOpenInstant().minusMillis(1)  // 진행 중인 봉 배제
                : Instant.now();                                   // 이미 확정된 봉만 존재
        String bucket = session.regularSessionActive() ? "ACTIVE" : "CLOSED";
        return prevCloseCache.getOrFetch(symbol, LocalDate.now(TimeZones.KST), bucket,
                () -> fetchPrevCloseUncached(symbol, before));
    }

    // ── trading DstInfo.isRegularSessionActive()/lastSessionOpenInstant() 복제 ──────────────
    // 스케쥴러 오케스트레이션(waitUntilOrderTime 등)과 무관한 "정규장 진행 여부 + 마지막 개장 시각"
    // 순수 KST/DST 계산만 필요하므로 DstInfo 전체를 참조하지 않고 이 2개 계산만 좁게 복제한다
    // (common/ 승격 대상 아님 — trading 스케쥴링 도메인 클래스 전체를 끌어오는 것이 과함)

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // 미국 뉴욕 기준 DST 여부에 따른 개장/마감/프리마켓 시각(KST) — DstInfo와 동일 시각표
    private static LocalTime marketOpenTime(boolean isDst)     { return isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30); }
    private static LocalTime marketCloseTime(boolean isDst)    { return isDst ? LocalTime.of(5, 0)   : LocalTime.of(6, 0); }
    private static LocalTime premarketStartTime(boolean isDst) { return isDst ? LocalTime.of(17, 0)  : LocalTime.of(18, 0); }

    // 정규장 진행 여부 + 가장 최근 개장 시각을 한 번에 계산한 결과
    // package-private — 테스트에서 시각 주입 시드로 직접 호출 (private record는 같은 패키지 테스트에서도 참조 불가)
    record MarketSessionInfo(boolean regularSessionActive, Instant lastSessionOpenInstant) {}

    // 현재 KST 기준 정규장 진행 여부 + 가장 최근 개장 시각 계산
    private static MarketSessionInfo resolveMarketSession() {
        return resolveMarketSessionAt(ZonedDateTime.now(TimeZones.KST));
    }

    // 시각 주입 테스트 시드 — DstInfo의 sessionAt()/lastSessionOpenInstantAt() 패턴과 동일
    static MarketSessionInfo resolveMarketSessionAt(ZonedDateTime nowKst) {
        boolean isDst = NY.getRules().isDaylightSavings(nowKst.toInstant());
        DayOfWeek day = nowKst.getDayOfWeek();
        LocalTime time = nowKst.toLocalTime();

        // 주말이거나 [장마감, 프리마켓시작) 구간이면 BLOCKED — 정규장 진행 중일 수 없음
        boolean blocked = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                || (!time.isBefore(marketCloseTime(isDst)) && time.isBefore(premarketStartTime(isDst)));
        // marketOpen~자정~marketClose 래핑 구간만 정규장 진행 중 (그 외 DIRECT 구간은 프리마켓)
        boolean regularSessionActive = !blocked
                && (!time.isBefore(marketOpenTime(isDst)) || time.isBefore(marketCloseTime(isDst)));

        // 가장 최근 개장 시각 — 자정~개장 전(00:00~marketOpen)이면 전날 저녁 개장을 가리켜야 함(날짜 롤백)
        LocalDate sessionDate = time.isBefore(marketOpenTime(isDst))
                ? nowKst.toLocalDate().minusDays(1)
                : nowKst.toLocalDate();
        Instant lastSessionOpenInstant = sessionDate.atTime(marketOpenTime(isDst)).atZone(TimeZones.KST).toInstant();

        return new MarketSessionInfo(regularSessionActive, lastSessionOpenInstant);
    }

    // 특정 거래일 확정 종가 — 일봉 캔들에서 해당 날짜 봉의 종가를 직접 조회 (라이브 현재가와 무관)
    // 애프터마켓 체결 포함 여부는 Toss 캔들 API 스펙상 정규장 마감 기준 확정 봉으로 간주 — 봉 없으면 현재가 폴백
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate) {
        try {
            return tossCandleApi.getCandles(ticker.name(), "1d", tradeDate, tradeDate).stream()
                    .filter(c -> c.date().equals(tradeDate))
                    .findFirst()
                    .map(TossCandle::close)
                    .orElseGet(() -> {
                        log.warn("Toss {} 확정 종가 캔들 없음, 현재가로 폴백: tradeDate={}", ticker, tradeDate);
                        return getPrice(ticker);
                    });
        } catch (Exception e) {
            log.warn("Toss {} 확정 종가 조회 실패, 현재가로 폴백: tradeDate={}, error={}", ticker, tradeDate, e.getMessage());
            return getPrice(ticker);
        }
    }

    // package-private — 테스트에서 before 시각 직접 주입 (DstInfo.calculate() 실시간 호출 우회)
    Optional<BigDecimal> fetchPrevCloseUncached(String symbol, Instant before) {
        try {
            Optional<TossCandle> candle = tossCandleApi.getCandleBefore(symbol, "1d", before);
            if (candle.isPresent()) {
                return Optional.of(candle.get().close());
            }
            log.warn("Toss 캔들 없음, prevClose=current 사용: symbol={}", symbol);
        } catch (Exception e) {
            log.warn("Toss 전일종가 조회 실패, prevClose=current 사용: symbol={}, error={}", symbol, e.getMessage());
        }
        return Optional.empty();
    }

    // ── TossStockInfoPort ──────────────────────────────────────────────────────

    public TossStockInfo getStockInfo(StrategyTicker ticker) {
        return stockInfoCache.getOrFetch(ticker.name(), () -> fetchStockInfoUncached(ticker))
                .orElseGet(() -> new TossStockInfo(ticker.name(), ticker.name(), ticker.name(), "", "USD", ""));
    }

    // stocks API 직접 호출 — 성공 응답만 Optional에 담아 캐싱, 실패/empty는 Optional.empty 반환
    private Optional<TossStockInfo> fetchStockInfoUncached(StrategyTicker ticker) {
        // stocks API는 복수형 파라미터(symbols) — 단건이어도 동일
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbols", ticker.name());

        // 공통 API — 관리자 토큰 사용
        TossResult<List<StockItem>> wrapper = tossHttpClient.getCommon(STOCKS_PATH, params,
                new ParameterizedTypeReference<TossResult<List<StockItem>>>() {});
        List<StockItem> items = wrapper != null ? wrapper.result() : null;
        if (items == null || items.isEmpty()) {
            log.warn("Toss 종목 정보 응답 없음: ticker={}", ticker);
            return Optional.empty();
        }
        StockItem s = items.get(0);
        return Optional.of(new TossStockInfo(
                s.symbol(),
                s.name()         != null ? s.name()         : ticker.name(),
                s.englishName()  != null ? s.englishName()  : ticker.name(),
                s.market()       != null ? s.market()       : "",
                s.currency()     != null ? s.currency()     : "USD",
                s.status()       != null ? s.status()       : ""
        ));
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — TossPriceApiTest에서 직접 생성하여 stub에 사용
    record PriceItem(
        @JsonProperty("symbol")    String symbol,    // 종목 코드 (예: SOXL)
        @JsonProperty("lastPrice") String lastPrice, // 현재가 (문자열 소수 형식)
        @JsonProperty("currency")  String currency   // 통화 (예: USD)
    ) {}

    // stocks API 응답 — 가격 정보 없음 (name/market/currency 등 기본 정보만)
    record StockItem(
        @JsonProperty("symbol")           String symbol,          // 종목 코드
        @JsonProperty("name")             String name,            // 한글 종목명
        @JsonProperty("englishName")      String englishName,     // 영문 종목명
        @JsonProperty("market")           String market,          // 거래소/시장
        @JsonProperty("currency")         String currency,        // 통화
        @JsonProperty("status")           String status,          // 종목 상태
        @JsonProperty("sharesOutstanding") String sharesOutstanding // 발행주식수
    ) {}
}
