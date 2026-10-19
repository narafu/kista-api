package com.kista.tradingcore.stats.adapter.out.coinbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.stats.application.port.output.CoinbaseMarketDataPort;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

// 코인베이스 Exchange 공개(무인증) 시세 — api.exchange.coinbase.com
@Component
class CoinbaseMarketDataAdapter implements CoinbaseMarketDataPort {

    private static final String BASE_URL = "https://api.exchange.coinbase.com";
    private static final int ONE_DAY_SECONDS = 86_400; // 일봉 granularity

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

    // start/end 없이 부르면 최신 300봉 — 프리미엄 추이 최대 200일에 충분해 페이징하지 않는다
    @Override
    public List<DatedValue> getUsdDailyCloses(String asset) {
        List<List<BigDecimal>> rows = restClient.get()
                .uri(BASE_URL + "/products/{p}/candles?granularity={g}", product(asset), ONE_DAY_SECONDS)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (rows == null) return List.of();
        // 행 구조: [0]=봉 시작(UTC epoch초), [1]=저가, [2]=고가, [3]=시가, [4]=종가, [5]=거래량 — 최신 봉이 먼저 온다
        return rows.stream()
                .map(r -> new DatedValue(Instant.ofEpochSecond(r.get(0).longValue()).atZone(ZoneOffset.UTC).toLocalDate(),
                        r.get(4)))
                .sorted(Comparator.comparing(DatedValue::date))
                .toList();
    }

    // 코인베이스 상품 ID: 자산 + -USD (예: BTC-USD)
    private static String product(String asset) {
        return asset + "-USD";
    }

    record TickerResponse(@JsonProperty("price") BigDecimal price) {}
}
