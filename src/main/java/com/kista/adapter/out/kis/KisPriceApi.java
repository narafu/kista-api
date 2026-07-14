package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
class KisPriceApi {

    private static final String SINGLE_PATH  = "/uapi/overseas-price/v1/quotations/price";
    private static final String SINGLE_TR_ID = "HHDFS00000300";
    private static final String MULTI_PATH   = "/uapi/overseas-price/v1/quotations/multprice";
    private static final String MULTI_TR_ID  = "HHDFS76220000";

    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;

    public BigDecimal getPrice(Ticker ticker, Account account) {
        // 현재가만 필요한 경우 — snapshot 조회 후 current 반환 (KIS API 호출 횟수 동일)
        return getPriceSnapshot(ticker, account).current();
    }

    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        if (tickers.isEmpty()) return Map.of();

        MultiPriceResponse response = fetchMultiPrice(tickers, account);

        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        if (response != null && response.output2() != null) {
            for (MultiPriceResponse.Output2 item : response.output2()) {
                if (item.symb() == null) continue;
                Optional<BigDecimal> resolved = resolveItemPrice(item, "복수종목 현재가");
                if (resolved.isEmpty()) continue;
                Ticker.tryParse(item.symb()).ifPresent(t -> result.put(t, resolved.get()));
            }
        } else {
            log.warn("복수종목 현재가 응답 없음: output2 null");
        }

        fillMissingBySingleCall(tickers, result, "복수종목 현재가", t -> getPrice(t, account));
        return result;
    }

    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        String excd = exchangeRegistry.excd(ticker);
        PriceResponse response = kisHttpClient.pricingGet(
                SINGLE_TR_ID, SINGLE_PATH, account, PriceResponse.class,
                p -> {
                    p.add("EXCD", excd);
                    p.add("SYMB", ticker.name());
                });
        if (response == null || response.output() == null) {
            throw new KisApiException("가격 조회 응답 없음: " + ticker, null);
        }
        String last = response.output().last();
        String base = response.output().base();
        if (last == null || last.isBlank()) log.info("단건 현재가 — last 빈값, base 사용: ticker={}, base={}", ticker, base);
        BigDecimal current = KisResponseParser.resolvePrice(last, base)
                .orElseThrow(() -> new KisApiException("가격 조회 응답 없음(last·base 모두 빈값): " + ticker, null));
        // prevClose: 단건 조회 응답의 base(전일종가) 필드 그대로 사용, 없으면 current로 fallback
        BigDecimal prevClose = Optional.ofNullable(base).filter(s -> !s.isBlank())
                .map(KisResponseParser::parseBd)
                .orElse(current);
        return new PriceSnapshot(current, prevClose);
    }

    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        if (tickers.isEmpty()) return Map.of();

        MultiPriceResponse response = fetchMultiPrice(tickers, account);
        if (response == null || response.output2() == null) {
            log.warn("복수종목 스냅샷 응답 없음: output2 null");
            return Map.of();
        }

        Map<Ticker, PriceSnapshot> result = new LinkedHashMap<>();
        for (MultiPriceResponse.Output2 item : response.output2()) {
            if (item.symb() == null) continue;
            Optional<BigDecimal> resolved = resolveItemPrice(item, "복수종목 스냅샷");
            if (resolved.isEmpty()) continue;
            Ticker ticker = Ticker.tryParse(item.symb()).orElse(null);
            if (ticker == null) {
                log.warn("복수종목 스냅샷 응답 — Ticker 매핑 실패(무시): symb={}", item.symb());
                continue;
            }
            BigDecimal current = resolved.get();
            // prevClose: 응답의 base(전일종가) 필드 그대로 사용, 없으면 current로 fallback
            BigDecimal prevClose = Optional.ofNullable(item.base()).filter(s -> !s.isBlank())
                    .map(KisResponseParser::parseBd)
                    .orElse(current);
            result.put(ticker, new PriceSnapshot(current, prevClose));
        }

        fillMissingBySingleCall(tickers, result, "복수종목 스냅샷", t -> getPriceSnapshot(t, account));
        return result;
    }

    // multprice(HHDFS76220000) 1회 호출 — NREC + 종목별 EXCD_nn/SYMB_nn 파라미터 구성
    private MultiPriceResponse fetchMultiPrice(List<Ticker> tickers, Account account) {
        return kisHttpClient.pricingGet(
                MULTI_TR_ID, MULTI_PATH, account, MultiPriceResponse.class,
                p -> {
                    p.add("NREC", String.valueOf(tickers.size()));
                    for (int i = 0; i < tickers.size(); i++) {
                        Ticker ticker = tickers.get(i);
                        String num = String.format("%02d", i + 1);
                        p.add("EXCD_" + num, exchangeRegistry.excd(ticker));
                        p.add("SYMB_" + num, ticker.name());
                    }
                });
    }

    // output2 항목의 현재가 파싱 — last 빈값 시 base fallback, 둘 다 빈값이면 warn 후 empty
    private Optional<BigDecimal> resolveItemPrice(MultiPriceResponse.Output2 item, String label) {
        Optional<BigDecimal> resolved = KisResponseParser.resolvePrice(item.last(), item.base());
        if (resolved.isEmpty()) {
            log.warn("{} 응답 항목 누락(last·base 모두 빈값): symb={}", label, item.symb());
            return Optional.empty();
        }
        if (item.last() == null || item.last().isBlank()) {
            log.info("{} — last 빈값, base 사용: symb={}, base={}", label, item.symb(), item.base());
        }
        return resolved;
    }

    // 복수종목 응답에 없는 종목을 단건 API로 보충 — 실패 종목은 결과에서 제외 (warn)
    private <V> void fillMissingBySingleCall(List<Ticker> tickers, Map<Ticker, V> result, String label,
                                             java.util.function.Function<Ticker, V> singleCall) {
        for (Ticker ticker : tickers) {
            if (result.containsKey(ticker)) continue;
            log.warn("{} 응답 누락 — 단건 API fallback 시도: ticker={}", label, ticker);
            try {
                result.put(ticker, singleCall.apply(ticker));
                log.info("단건 API fallback 성공: ticker={}", ticker);
            } catch (Exception e) {
                log.warn("단건 API fallback도 실패: ticker={}", ticker);
            }
        }
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
