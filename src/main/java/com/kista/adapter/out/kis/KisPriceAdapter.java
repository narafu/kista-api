package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
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

    private static final String SINGLE_PATH  = "/uapi/overseas-price/v1/quotations/price";
    private static final String SINGLE_TR_ID = "HHDFS00000300";
    private static final String MULTI_PATH   = "/uapi/overseas-price/v1/quotations/multprice";
    private static final String MULTI_TR_ID  = "HHDFS76220000";

    private final KisHttpClient kisHttpClient;

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(SINGLE_TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        // KIS exchangeCode: NASD→NAS, AMS→AMS
        String excd = ticker.getExcdCode().name();
        params.add("EXCD", excd);
        params.add("SYMB", ticker.name());

        PriceResponse response = kisHttpClient.get(SINGLE_PATH, headers, params, PriceResponse.class);

        if (response == null || response.output() == null) {
            throw new KisApiException("가격 조회 응답 없음: " + ticker, null);
        }
        // last(현재가) 우선, 비어있으면 base(전일종가) fallback
        String last = response.output().last();
        String base = response.output().base();
        String price = (last != null && !last.isBlank()) ? last
                     : (base != null && !base.isBlank()) ? base
                     : null;
        if (price == null) {
            throw new KisApiException("가격 조회 응답 없음(last·base 모두 빈값): " + ticker, null);
        }
        if (last == null || last.isBlank()) {
            log.info("단건 현재가 — last 빈값, base 사용: ticker={}, base={}", ticker, base);
        }
        return new BigDecimal(price);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        if (tickers.isEmpty()) return Map.of();

        HttpHeaders headers = kisHttpClient.buildHeaders(MULTI_TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        params.add("NREC", String.valueOf(tickers.size()));

        for (int i = 0; i < tickers.size(); i++) {
            Ticker ticker = tickers.get(i);
            String num = String.format("%02d", i + 1); // "01", "02", "03"
            String excd = ticker.getExcdCode().name();
            params.add("EXCD_" + num, excd);
            params.add("SYMB_" + num, ticker.name());
        }

        MultiPriceResponse response = kisHttpClient.get(MULTI_PATH, headers, params, MultiPriceResponse.class);

        if (response == null || response.output2() == null) {
            log.warn("복수종목 현재가 응답 없음: output2 null");
            return Map.of();
        }

        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        for (MultiPriceResponse.Output2 item : response.output2()) {
            if (item.symb() == null) continue;
            // last(현재가) 우선, 비어있으면 base(전일종가) fallback — 장 마감 시 last가 빈 ETF 대응
            String price = (item.last() != null && !item.last().isBlank()) ? item.last()
                         : (item.base() != null && !item.base().isBlank()) ? item.base()
                         : null;
            if (price == null) {
                log.warn("복수종목 현재가 응답 항목 누락(last·base 모두 빈값): symb={}", item.symb());
                continue;
            }
            if (item.last() == null || item.last().isBlank()) {
                log.info("복수종목 현재가 — last 빈값, base 사용: symb={}, base={}", item.symb(), item.base());
            }
            Ticker t = Ticker.tryParse(item.symb()).orElse(null);
            if (t == null) {
                log.warn("복수종목 현재가 응답 — Ticker 매핑 실패(무시): symb={}", item.symb());
                continue;
            }
            result.put(t, KisResponseParser.parseBd(price));
        }

        // 복수종목 응답에 없는 종목 → 단건 API fallback
        for (Ticker ticker : tickers) {
            if (!result.containsKey(ticker)) {
                log.warn("복수종목 현재가 응답 누락 — 단건 API fallback 시도: ticker={}, excd={}", ticker, ticker.getExchangeCode());
                try {
                    BigDecimal price = getPrice(ticker, account);
                    result.put(ticker, price);
                    log.info("단건 API fallback 성공: ticker={}, price={}", ticker, price);
                } catch (Exception e) {
                    log.warn("단건 API fallback도 실패: ticker={}", ticker);
                }
            }
        }
        return result;
    }

    record PriceResponse(@JsonProperty("output") Output output) {
        record Output(
            @JsonProperty("last") String last,
            @JsonProperty("base") String base  // 전일종가 — last 빈값 시 fallback
        ) {}
    }

    record MultiPriceResponse(
        @JsonProperty("output") Output output,
        @JsonProperty("output2") List<Output2> output2
    ) {
        record Output(@JsonProperty("nrec") String nrec) {}

        // symb: 종목코드, last: 현재가(체결가), base: 전일종가(last 빈값 시 fallback)
        record Output2(
            @JsonProperty("symb") String symb,
            @JsonProperty("last") String last,
            @JsonProperty("base") String base
        ) {}
    }
}
