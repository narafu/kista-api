package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.TosAccountPort;
import com.kista.domain.port.out.TosMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TosHoldingsApi implements TosAccountPort, TosMarginPort {

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

        // 통합증거금(USD+KRW→USD) 기준 — USD 현금만인 getBuyableAmount()와 달리 매매 수식 일관성 보장
        BigDecimal usdDeposit = getMarginItems(account).stream()
                .findFirst()
                .map(MarginItem::purchasableAmount)
                .orElse(BigDecimal.ZERO);

        if (holdingsResponse == null || holdingsResponse.items() == null) {
            return new AccountBalance(0, null, usdDeposit);
        }

        // 요청 종목 필터링 후 AccountBalance 구성 (미보유 시 holdings=0, avgPrice=null)
        return holdingsResponse.items().stream()
                .filter(i -> ticker.name().equals(i.symbol()))
                .findFirst()
                .map(i -> {
                    int qty = Integer.parseInt(i.quantity());
                    BigDecimal avg = qty > 0 ? new BigDecimal(i.averagePurchasePrice()) : null;
                    return new AccountBalance(qty, avg, usdDeposit);
                })
                .orElse(new AccountBalance(0, null, usdDeposit));
    }

    @Override
    public BigDecimal getBuyableAmount(Account account) {
        return fetchBuyingPower(account, "USD");
    }

    @Override
    public List<MarginItem> getMarginItems(Account account) {
        // USD·KRW 예수금 조회 후 환율 기반 USD 합산 — Toss 통합예수금
        BigDecimal usdBuyable = fetchBuyingPower(account, "USD");
        BigDecimal krwBuyable = fetchBuyingPower(account, "KRW");
        BigDecimal usdToKrwRate = fetchUsdToKrwRate(account);

        // KRW → USD 환산 후 합산 (환율 0이면 KRW 무시)
        BigDecimal krwAsUsd = usdToKrwRate.compareTo(BigDecimal.ZERO) > 0
                ? krwBuyable.divide(usdToKrwRate, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalUsd = usdBuyable.add(krwAsUsd).setScale(2, RoundingMode.HALF_UP);

        return List.of(new MarginItem(Currency.USD, BigDecimal.ZERO, BigDecimal.ZERO, totalUsd, usdToKrwRate));
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
