package com.kista.tradingcore.broker.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.toss.TossApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPriceApi {

    // Toss 가격 API: GET /api/v1/prices?symbols=SOXL,TQQQ (콤마 구분, 최대 200개)
    private static final String PRICES_PATH = "/api/v1/prices";

    private static final ParameterizedTypeReference<TossResult<List<PriceItem>>> PRICES_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TossHttpClient tossHttpClient;

    // 계좌 토큰 현재가 — 계좌별 값이라 캐시하지 않는다. 응답에 없는 종목은 키 부재(= 그 종목 실패), 응답 자체가 없으면 예외
    public Map<StrategyTicker, BigDecimal> currentPrices(BrokerAccountRef account, Collection<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();
        return parsePrices(tossHttpClient.get(PRICES_PATH, account, symbolsParam(tickers), PRICES_TYPE), tickers);
    }

    // symbols 쿼리 파라미터: 콤마 구분 종목 코드 목록
    private static MultiValueMap<String, String> symbolsParam(Collection<StrategyTicker> tickers) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbols", tickers.stream().map(StrategyTicker::name).collect(Collectors.joining(",")));
        return params;
    }

    // 응답 파싱
    private static Map<StrategyTicker, BigDecimal> parsePrices(TossResult<List<PriceItem>> wrapper, Collection<StrategyTicker> tickers) {
        List<PriceItem> items = wrapper != null ? wrapper.result() : null;

        // 응답 자체가 없는 전체 실패 — 조용히 빈 Map을 반환하면 호출부가 가격 0으로 오인
        if (items == null) {
            throw new TossApiException("Toss 가격 조회 응답 없음(전체 실패): tickers=" + tickers, null);
        }

        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (var item : items) {
            Optional<StrategyTicker> ticker = StrategyTicker.tryParse(item.symbol()); // StrategyTicker 외 종목(예: AAPL) silent drop
            if (ticker.isEmpty()) continue;
            // lastPrice null·비숫자·0 이하는 그 종목만 건너뜀(대체값 금지)
            try {
                BigDecimal price = new BigDecimal(item.lastPrice());
                if (price.signum() > 0) {
                    result.putIfAbsent(ticker.get(), price);
                    continue;
                }
            } catch (NumberFormatException | NullPointerException _) {
                // 아래 warn으로 처리
            }
            log.warn("Toss 현재가 파싱 실패 — 종목 건너뜀: symbol={}, lastPrice={}", item.symbol(), item.lastPrice());
        }
        return result;
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — TossPriceApiTest에서 직접 생성하여 stub에 사용
    record PriceItem(
        @JsonProperty("symbol")    String symbol,    // 종목 코드 (예: SOXL)
        @JsonProperty("lastPrice") String lastPrice  // 현재가 (문자열 소수 형식)
    ) {}
}
