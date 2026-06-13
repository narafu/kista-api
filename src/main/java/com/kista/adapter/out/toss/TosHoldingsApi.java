package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.TosAccountPort;
import com.kista.domain.port.out.TosMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TosHoldingsApi implements TosAccountPort, TosMarginPort {

    // Toss 보유주식 API 경로
    private static final String HOLDINGS_PATH = "/api/v1/holdings";
    // Toss 매수 가능 금액 API 경로 (GET /api/v1/buying-power?currency=USD)
    private static final String BUYING_POWER_PATH = "/api/v1/buying-power";

    private final TossHttpClient tossHttpClient;

    @Override
    public AccountBalance getBalance(Account account, Ticker ticker) {
        // 보유 종목 조회
        HoldingsResponse holdingsResponse = tossHttpClient.get(
                HOLDINGS_PATH, tossHttpClient.buildHeaders(account),
                new LinkedMultiValueMap<>(), HoldingsResponse.class);

        // USD 매수가능금액 조회
        BigDecimal usdDeposit = getBuyableAmount(account);

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
        // currency=USD 쿼리 파라미터 필수 — 미전송 시 400 "유효하지 않은 주문 ID" 오류
        // 응답 래퍼: {"result": {"cashBuyingPower": ..., "currency": ...}}
        var params = new LinkedMultiValueMap<String, String>();
        params.add("currency", "USD");
        BuyingPowerWrapper wrapper = tossHttpClient.get(
                BUYING_POWER_PATH, tossHttpClient.buildHeaders(account),
                params, BuyingPowerWrapper.class);
        if (wrapper == null || wrapper.result() == null || wrapper.result().cashBuyingPower() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(wrapper.result().cashBuyingPower());
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
}
