package com.kista.broker.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.SellableQuantity;
import com.kista.broker.domain.model.Currency;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.broker.domain.model.toss.TossExchangeRate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
class TossHoldingsApi {

    // Toss 보유주식 API 경로
    private static final String HOLDINGS_PATH = "/api/v1/holdings";
    // Toss 매수 가능 금액 API 경로 (GET /api/v1/buying-power?currency=USD|KRW)
    private static final String BUYING_POWER_PATH = "/api/v1/buying-power";
    // Toss 환율 API 경로 (GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW)
    private static final String EXCHANGE_RATE_PATH = "/api/v1/exchange-rate";
    // Toss 판매 가능 수량 API 경로
    private static final String SELLABLE_QUANTITY_PATH = "/api/v1/sellable-quantity";

    private final TossHttpClient tossHttpClient;
    // USD/KRW 환율 60초 TTL 캐시 — 계좌 무관 전역 스칼라 1개
    private final UsdKrwRateCache exchangeRateCache = new UsdKrwRateCache(Duration.ofSeconds(60), Instant::now);

    public BrokerBalance getBalance(BrokerAccountRef account, Ticker ticker) {
        // 보유 종목 조회 — 응답 {"result": {"items": [...]}} TossResult 제네릭 래퍼 구조
        TossResult<HoldingsResponse> wrapper = tossHttpClient.get(
                HOLDINGS_PATH, account, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<TossResult<HoldingsResponse>>() {});
        HoldingsResponse holdingsResponse = wrapper != null ? wrapper.result() : null;

        // 토스 API는 USD 예수금만 주문 가능 — KRW는 미국주식 주문에 자동환전 안 됨
        BigDecimal usdDeposit = getUsdBuyableAmount(account);

        if (holdingsResponse == null || holdingsResponse.items() == null) {
            return new BrokerBalance(0, null, usdDeposit);
        }

        // 요청 종목 필터링 후 BrokerBalance 구성 (미보유 시 holdings=0, avgPrice=null)
        return holdingsResponse.items().stream()
                .filter(i -> ticker.name().equals(i.symbol()))
                .findFirst()
                .map(i -> {
                    int quantity = Integer.parseInt(i.quantity());
                    BigDecimal avg = quantity > 0 ? new BigDecimal(i.averagePurchasePrice()) : null;
                    return new BrokerBalance(quantity, avg, usdDeposit);
                })
                .orElse(new BrokerBalance(0, null, usdDeposit));
    }

    // ── TossMarginPort ─────────────────────────────────────────────────────────

    public BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        return fetchBuyingPower(account, "USD");
    }

    // USD·KRW 예수금 통화별 조회 (통합 아님 — UI 표시용)
    public List<MarginItem> getMargin(BrokerAccountRef account) {
        // USD·KRW 예수금·환율 3개 독립 HTTP 호출을 virtual thread로 병렬 실행
        BigDecimal usdBuyable;
        BigDecimal krwBuyable;
        BigDecimal usdToKrwRate;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BigDecimal> usdFuture = executor.submit(() -> fetchBuyingPower(account, "USD"));
            Future<BigDecimal> krwFuture = executor.submit(() -> fetchBuyingPower(account, "KRW"));
            Future<BigDecimal> rateFuture = executor.submit(this::fetchUsdToKrwRate);
            usdBuyable = await(usdFuture);
            krwBuyable = await(krwFuture);
            usdToKrwRate = await(rateFuture);
        }

        // 잔고 진단 로그 — cashBuyingPower API 실제 반환값 확인용
        log.info("Toss 예수금 조회: USD=${}, KRW=₩{}, 환율={}", usdBuyable, krwBuyable, usdToKrwRate);

        return List.of(
                new MarginItem(Currency.USD, BigDecimal.ZERO, BigDecimal.ZERO, usdBuyable, usdToKrwRate),
                new MarginItem(Currency.KRW, BigDecimal.ZERO, BigDecimal.ZERO, krwBuyable, usdToKrwRate)
        );
    }

    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        // 1~4. 보유 종목·USD·KRW 예수금·환율 4개 독립 HTTP 호출을 virtual thread로 병렬 실행
        HoldingsResponse holdingsResponse;
        BigDecimal usdDeposit;
        BigDecimal krwDeposit;
        BigDecimal rate;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TossResult<HoldingsResponse>> holdingsFuture = executor.submit(() -> tossHttpClient.get(
                    HOLDINGS_PATH, account, new LinkedMultiValueMap<>(),
                    new ParameterizedTypeReference<TossResult<HoldingsResponse>>() {}));
            Future<BigDecimal> usdFuture = executor.submit(() -> fetchBuyingPower(account, "USD"));
            Future<BigDecimal> krwFuture = executor.submit(() -> fetchBuyingPower(account, "KRW"));
            Future<BigDecimal> rateFuture = executor.submit(this::fetchUsdToKrwRate);

            TossResult<HoldingsResponse> holdingsWrapper = await(holdingsFuture);
            holdingsResponse = holdingsWrapper != null ? holdingsWrapper.result() : null;
            usdDeposit = await(usdFuture);
            krwDeposit = await(krwFuture);
            rate = await(rateFuture);
        }

        // 5. Ticker 파싱 성공·수량 > 0 항목만 원시 보유값으로 추출 (계산은 도메인 위임)
        List<PresentBalanceResult.TossHolding> holdings = List.of();
        if (holdingsResponse != null && holdingsResponse.items() != null) {
            holdings = holdingsResponse.items().stream()
                    .filter(h -> h.lastPrice() != null && !h.lastPrice().isBlank())
                    .flatMap(h -> {
                        Optional<Ticker> tickerOpt = Ticker.tryParse(h.symbol());
                        if (tickerOpt.isEmpty()) return Stream.empty();
                        int quantity = Integer.parseInt(h.quantity());
                        if (quantity <= 0) return Stream.empty();
                        return Stream.of(new PresentBalanceResult.TossHolding(
                                tickerOpt.get(), quantity,
                                new BigDecimal(h.averagePurchasePrice()),
                                new BigDecimal(h.lastPrice())
                        ));
                    })
                    .toList();
        }

        // 6. KRW 환산·총자산·수익률 집계는 도메인 팩토리에 위임
        return PresentBalanceResult.aggregateToss(holdings, usdDeposit, krwDeposit, rate);
    }

    // currency 파라미터로 매수가능금액 단건 조회
    private BigDecimal fetchBuyingPower(BrokerAccountRef account, String currencyCode) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("currency", currencyCode);
        TossResult<BuyableAmountResponse> wrapper = tossHttpClient.get(
                BUYING_POWER_PATH, account, params,
                new ParameterizedTypeReference<TossResult<BuyableAmountResponse>>() {});
        if (wrapper == null || wrapper.result() == null || wrapper.result().cashBuyingPower() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(wrapper.result().cashBuyingPower());
    }

    // USD/KRW 환율 조회 (1 USD = ? KRW) — 공통 API, 관리자 토큰 사용
    private BigDecimal fetchUsdToKrwRate() {
        return getExchangeRate().rate();
    }

    // ── TossExchangeRatePort ───────────────────────────────────────────────────

    public TossExchangeRate getExchangeRate() {
        // 60초 TTL 캐시 경유 — 성공 값만 캐싱, ZERO 폴백·예외는 그대로 전파
        return exchangeRateCache.getOrFetch(this::fetchExchangeRateUncached);
    }

    // 캐시 미적용 원본 환율 조회 (UsdKrwRateCache의 fetcher로만 사용)
    private TossExchangeRate fetchExchangeRateUncached() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("baseCurrency", "USD");
        params.add("quoteCurrency", "KRW");
        // 공통 API — 관리자 토큰 사용
        TossResult<ExchangeRateResult> wrapper = tossHttpClient.getCommon(
                EXCHANGE_RATE_PATH, params,
                new ParameterizedTypeReference<TossResult<ExchangeRateResult>>() {});
        if (wrapper == null || wrapper.result() == null) {
            return new TossExchangeRate(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        ExchangeRateResult r = wrapper.result();
        BigDecimal rate    = r.rate()    != null ? new BigDecimal(r.rate()).round(new MathContext(6))    : BigDecimal.ZERO;
        BigDecimal midRate = r.midRate() != null ? new BigDecimal(r.midRate()).round(new MathContext(6)) : BigDecimal.ZERO;
        return new TossExchangeRate(rate, midRate);
    }

    // ── TossSellableQuantityPort ───────────────────────────────────────────────

    public SellableQuantity getSellableQuantity(Ticker ticker, BrokerAccountRef account) {
        return fetchSellableQuantity(ticker, account);
    }

    // 내부 헬퍼: Toss 판매 가능 수량 조회
    private SellableQuantity fetchSellableQuantity(Ticker ticker, BrokerAccountRef account) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("symbol", ticker.name());
        TossResult<SellableQuantityResult> wrapper = tossHttpClient.get(
                SELLABLE_QUANTITY_PATH, account, params,
                new ParameterizedTypeReference<TossResult<SellableQuantityResult>>() {});
        if (wrapper == null || wrapper.result() == null) {
            log.warn("Toss 판매 가능 수량 응답 없음: ticker={}, wrapper={}", ticker, wrapper);
            return new SellableQuantity(ticker.name(), 0);
        }
        SellableQuantityResult result = wrapper.result();
        int quantity = TossResponseParser.parseIntOrZero(result.sellableQuantity());
        log.info("Toss 판매 가능 수량: ticker={}, sellableQuantity={}", ticker, quantity);
        return new SellableQuantity(ticker.name(), quantity);
    }

    // Future 결과 대기 — ExecutionException을 언래핑해 TossApiException 등 원본 예외를 호출자에 그대로 전파
    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw new TossApiException("Toss 병렬 조회 실패: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TossApiException("Toss 병렬 조회 인터럽트", e);
        }
    }

    // package-private — TossHoldingsApiTest에서 직접 생성하여 stub에 사용
    record HoldingsResponse(@JsonProperty("items") List<HoldingItem> items) {}

    record HoldingItem(
        @JsonProperty("symbol") String symbol,                              // 종목 코드 (예: SOXL)
        @JsonProperty("quantity") String quantity,                          // 보유 수량 (문자열)
        @JsonProperty("averagePurchasePrice") String averagePurchasePrice,  // 평균 매입가 (문자열)
        @JsonProperty("lastPrice") String lastPrice                         // 현재가 (문자열, 정보성)
    ) {}

    record BuyableAmountResponse(
        @JsonProperty("cashBuyingPower") String cashBuyingPower, // 현금 기반 매수 가능 금액 (미수 미발생 기준)
        @JsonProperty("currency") String currency                // 통화 (예: USD)
    ) {}

    record ExchangeRateResult(
        @JsonProperty("rate")    String rate,    // 매수 환율 (1 USD = ? KRW)
        @JsonProperty("midRate") String midRate  // 매매기준율
    ) {}

    record SellableQuantityResult(
        @JsonProperty("sellableQuantity") String sellableQuantity  // 판매 가능 수량
    ) {}
}
