package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.TosAccountPort;
import com.kista.domain.port.out.TosMarginPort;
import com.kista.domain.port.out.TossPortfolioPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class TosHoldingsApi implements TosAccountPort, TosMarginPort, TossPortfolioPort {

    // Toss 보유주식 API 경로
    private static final String HOLDINGS_PATH = "/api/v1/holdings";
    // Toss 매수 가능 금액 API 경로 (GET /api/v1/buying-power?currency=USD|KRW)
    private static final String BUYING_POWER_PATH = "/api/v1/buying-power";
    // Toss 환율 API 경로 (GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW)
    private static final String EXCHANGE_RATE_PATH = "/api/v1/exchange-rate";

    private final TossHttpClient tossHttpClient;

    @Override
    public AccountBalance getBalance(Account account, Ticker ticker) {
        // 보유 종목 조회
        HoldingsResponse holdingsResponse = tossHttpClient.get(
                HOLDINGS_PATH, account, new LinkedMultiValueMap<>(), HoldingsResponse.class);

        // 토스 API는 USD 예수금만 주문 가능 — KRW는 미국주식 주문에 자동환전 안 됨
        BigDecimal usdDeposit = getBuyableAmount(account);

        if (holdingsResponse == null || holdingsResponse.items() == null) {
            return new AccountBalance(0, null, usdDeposit);
        }

        // 요청 종목 필터링 후 AccountBalance 구성 (미보유 시 holdings=0, avgPrice=null)
        return holdingsResponse.items().stream()
                .filter(i -> ticker.name().equals(i.symbol()))
                .findFirst()
                .map(i -> {
                    int quantity = Integer.parseInt(i.quantity());
                    BigDecimal avg = quantity > 0 ? new BigDecimal(i.averagePurchasePrice()) : null;
                    return new AccountBalance(quantity, avg, usdDeposit);
                })
                .orElse(new AccountBalance(0, null, usdDeposit));
    }

    @Override
    public BigDecimal getBuyableAmount(Account account) {
        return fetchBuyingPower(account, "USD");
    }

    @Override
    public List<MarginItem> getMarginItems(Account account) {
        // USD·KRW 예수금 통화별 조회 (통합 아님 — UI 표시용)
        BigDecimal usdBuyable = fetchBuyingPower(account, "USD");
        BigDecimal krwBuyable = fetchBuyingPower(account, "KRW");
        BigDecimal usdToKrwRate = fetchUsdToKrwRate(account);

        // 잔고 진단 로그 — cashBuyingPower API 실제 반환값 확인용
        log.info("Toss 예수금 조회: USD=${}, KRW=₩{}, 환율={}", usdBuyable, krwBuyable, usdToKrwRate);

        return List.of(
                new MarginItem(Currency.USD, BigDecimal.ZERO, BigDecimal.ZERO, usdBuyable, usdToKrwRate),
                new MarginItem(Currency.KRW, BigDecimal.ZERO, BigDecimal.ZERO, krwBuyable, usdToKrwRate)
        );
    }

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        // 1. 전체 보유 종목 조회
        HoldingsResponse holdingsResponse = tossHttpClient.get(
                HOLDINGS_PATH, account, new LinkedMultiValueMap<>(), HoldingsResponse.class);
        // 2~4. USD·KRW 예수금 및 환율 조회
        BigDecimal usdDeposit = fetchBuyingPower(account, "USD");
        BigDecimal krwDeposit = fetchBuyingPower(account, "KRW");
        BigDecimal rate = fetchUsdToKrwRate(account);

        // 5. Ticker 파싱 성공·수량 > 0 항목만 Item 변환
        List<PresentBalanceResult.Item> items = List.of();
        if (holdingsResponse != null && holdingsResponse.items() != null) {
            items = holdingsResponse.items().stream()
                    .filter(h -> h.lastPrice() != null && !h.lastPrice().isBlank())
                    .flatMap(h -> {
                        Optional<Ticker> tickerOpt = Ticker.tryParse(h.symbol());
                        if (tickerOpt.isEmpty()) return Stream.empty();
                        int quantity = Integer.parseInt(h.quantity());
                        if (quantity <= 0) return Stream.empty();
                        BigDecimal lastPrice = new BigDecimal(h.lastPrice());
                        BigDecimal avgPrice = new BigDecimal(h.averagePurchasePrice());
                        BigDecimal evalAmountUsd = lastPrice.multiply(BigDecimal.valueOf(quantity))
                                .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal profitLossUsd = lastPrice.subtract(avgPrice)
                                .multiply(BigDecimal.valueOf(quantity))
                                .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal profitRate = avgPrice.compareTo(BigDecimal.ZERO) > 0
                                ? lastPrice.subtract(avgPrice)
                                  .divide(avgPrice, 4, RoundingMode.HALF_UP)
                                  .multiply(new BigDecimal("100"))
                                  .setScale(2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
                        return Stream.of(new PresentBalanceResult.Item(
                                tickerOpt.get(), quantity, avgPrice, lastPrice,
                                evalAmountUsd, profitLossUsd, profitRate, "AMEX"
                        ));
                    })
                    .toList();
        }

        // 6. KRW 기준 합산
        BigDecimal totalEvalUsd = items.stream().map(PresentBalanceResult.Item::evalAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfitUsd = items.stream().map(PresentBalanceResult.Item::profitLossUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPurchaseUsd = items.stream()
                .map(item -> item.avgPrice().multiply(BigDecimal.valueOf(item.holdings())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // totalAssetKrw = (포지션USD + USD예수금) × 환율 + KRW예수금
        BigDecimal totalAssetKrw = rate.compareTo(BigDecimal.ZERO) > 0
                ? totalEvalUsd.add(usdDeposit).multiply(rate).add(krwDeposit)
                  .setScale(0, RoundingMode.HALF_UP)
                : krwDeposit.setScale(0, RoundingMode.HALF_UP);
        // totalEvalProfitKrw = 평가손익USD × 환율
        BigDecimal totalEvalProfitKrw = rate.compareTo(BigDecimal.ZERO) > 0
                ? totalProfitUsd.multiply(rate).setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // totalReturnRate = 평가손익KRW / 매입금액KRW × 100
        BigDecimal totalPurchaseKrw = rate.compareTo(BigDecimal.ZERO) > 0
                ? totalPurchaseUsd.multiply(rate).setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalReturnRate = totalPurchaseKrw.compareTo(BigDecimal.ZERO) > 0
                ? totalEvalProfitKrw.divide(totalPurchaseKrw, 4, RoundingMode.HALF_UP)
                  .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal krwAsUsd = rate.compareTo(BigDecimal.ZERO) > 0
                ? krwDeposit.divide(rate, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalUsdDeposit = usdDeposit.add(krwAsUsd).setScale(2, RoundingMode.HALF_UP);
        return new PresentBalanceResult(items, totalAssetKrw, totalEvalProfitKrw, totalReturnRate, totalUsdDeposit, rate);
    }

    // currency 파라미터로 매수가능금액 단건 조회
    private BigDecimal fetchBuyingPower(Account account, String currencyCode) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("currency", currencyCode);
        BuyingPowerWrapper wrapper = tossHttpClient.get(
                BUYING_POWER_PATH, account, params, BuyingPowerWrapper.class);
        if (wrapper == null || wrapper.result() == null || wrapper.result().cashBuyingPower() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(wrapper.result().cashBuyingPower());
    }

    // USD/KRW 환율 조회 (1 USD = ? KRW) — 계좌 컨텍스트 불필요
    private BigDecimal fetchUsdToKrwRate(Account account) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("baseCurrency", "USD");
        params.add("quoteCurrency", "KRW");
        ExchangeRateWrapper wrapper = tossHttpClient.getNoAccountHeader(
                EXCHANGE_RATE_PATH, account, params, ExchangeRateWrapper.class);
        if (wrapper == null || wrapper.result() == null || wrapper.result().rate() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(wrapper.result().rate()).round(new MathContext(6));
    }

    // package-private — TosHoldingsApiTest에서 직접 생성하여 stub에 사용
    record HoldingsResponse(@JsonProperty("items") List<HoldingItem> items) {}

    record HoldingItem(
        @JsonProperty("symbol") String symbol,                              // 종목 코드 (예: SOXL)
        @JsonProperty("quantity") String quantity,                          // 보유 수량 (문자열)
        @JsonProperty("averagePurchasePrice") String averagePurchasePrice,  // 평균 매입가 (문자열)
        @JsonProperty("lastPrice") String lastPrice                         // 현재가 (문자열, 정보성)
    ) {}

    // GET /api/v1/buying-power 응답 래퍼 — {"result": {...}}
    record BuyingPowerWrapper(@JsonProperty("result") BuyableAmountResponse result) {}

    record BuyableAmountResponse(
        @JsonProperty("cashBuyingPower") String cashBuyingPower, // 현금 기반 매수 가능 금액 (미수 미발생 기준)
        @JsonProperty("currency") String currency                // 통화 (예: USD)
    ) {}

    // GET /api/v1/exchange-rate 응답 래퍼 — {"result": {...}}
    record ExchangeRateWrapper(@JsonProperty("result") ExchangeRateResult result) {}

    record ExchangeRateResult(
        @JsonProperty("rate") String rate,           // 매수 환율 (1 USD = ? KRW)
        @JsonProperty("midRate") String midRate      // 매매기준율
    ) {}
}
