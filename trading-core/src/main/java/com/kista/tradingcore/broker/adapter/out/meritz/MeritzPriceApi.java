package com.kista.tradingcore.broker.adapter.out.meritz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// 메리츠 해외주식 현재가 — prices API
@Component
@RequiredArgsConstructor
class MeritzPriceApi {

    private static final String PRICE_PATH = "/market/v1/overseas/prices";

    private final MeritzHttpClient http;
    private final MeritzExchangeRegistry exchangeRegistry;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceRow(@JsonProperty("prpr") String prpr) {}

    BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("mrkt_div_code", "OV");
        p.add("mrkt_cls_code", exchangeRegistry.marketCode(ticker));
        p.add("iscd", ticker.name());
        p.add("dely_rltm_cls_code", "0"); // 실시간
        PriceRow row = http.rows(http.get(PRICE_PATH, account, p), PriceRow.class);
        BigDecimal current = row == null ? null : MeritzResponse.decimal(row.prpr());
        // 0/없음은 호출부가 0원 매매를 하지 않도록 예외 — null 반환 금지
        if (current == null || current.signum() <= 0) {
            throw MeritzApiException.rejected(null, "메리츠 현재가 없음/0: ticker=" + ticker);
        }
        return current;
    }

    Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return perTicker(tickers, t -> getPrice(t, account));
    }

    // 메리츠 단건 API를 티커마다 호출해 입력 순서대로 담는다(LinkedHashMap — 순서 보존, null 값 허용)
    private static <V> Map<StrategyTicker, V> perTicker(List<StrategyTicker> tickers, Function<StrategyTicker, V> fetch) {
        Map<StrategyTicker, V> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) result.put(t, fetch.apply(t));
        return result;
    }
}
