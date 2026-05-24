package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.KisPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
@RequiredArgsConstructor
@Slf4j
public class KisPriceAdapter implements KisPricePort {

    private static final String PATH  = "/uapi/overseas-price/v1/quotations/price";
    private static final String TR_ID = "HHDFS00000300";

    private final KisHttpClient kisHttpClient;

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        // KIS exchangeCode: NASD→NAS, AMS→AMS
        String excd = ticker.getExchangeCode().equals("NASD") ? "NAS" : ticker.getExchangeCode();
        params.add("EXCD", excd);
        params.add("SYMB", ticker.name());

        PriceResponse response = kisHttpClient.get(PATH, headers, params, PriceResponse.class);

        if (response == null || response.output() == null || response.output().last() == null) {
            throw new IllegalStateException("가격 조회 실패: " + ticker);
        }
        return new BigDecimal(response.output().last());
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        // 검증된 단건 가격 API(HHDFS00000300)로 각 종목 순차 조회 — 복수 호가 API(HHDFS76410000) 미사용
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        for (Ticker ticker : tickers) {
            try {
                BigDecimal price = getPrice(ticker, account);
                result.put(ticker, price);
            } catch (Exception e) {
                log.warn("종목 가격 조회 실패 (skip): ticker={}, error={}", ticker, e.getMessage());
            }
        }
        return result;
    }

    record PriceResponse(@JsonProperty("output") Output output) {
        record Output(@JsonProperty("last") String last) {}
    }
}
