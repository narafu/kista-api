package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.KisPricePort;
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
public class KisPriceApi implements KisPricePort {

    private static final String SINGLE_PATH  = "/uapi/overseas-price/v1/quotations/price";
    private static final String SINGLE_TR_ID = "HHDFS00000300";
    private static final String MULTI_PATH   = "/uapi/overseas-price/v1/quotations/multprice";
    private static final String MULTI_TR_ID  = "HHDFS76220000";

    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        // 현재가만 필요한 경우 — snapshot 조회 후 current 반환 (KIS API 호출 횟수 동일)
        return getPriceSnapshot(ticker, account).current();
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        // 현재가만 필요한 경우 — snapshot 조회 후 current만 매핑 (multprice 1회 + fallback 동일)
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        getPriceSnapshots(tickers, account).forEach((t, s) -> result.put(t, s.current()));
        return result;
    }

    @Override
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
        // prevClose: base 우선, 없으면 current로 fallback (장 개시 직전 등 base 빈값 방어)
        BigDecimal prevClose = Optional.ofNullable(base).filter(s -> !s.isBlank())
                .map(KisResponseParser::parseBd)
                .orElse(current);
        return new PriceSnapshot(current, prevClose);
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        if (tickers.isEmpty()) return Map.of();

        MultiPriceResponse response = kisHttpClient.pricingGet(
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

        if (response == null || response.output2() == null) {
            log.warn("복수종목 스냅샷 응답 없음: output2 null");
            return Map.of();
        }

        Map<Ticker, PriceSnapshot> result = new LinkedHashMap<>();
        for (MultiPriceResponse.Output2 item : response.output2()) {
            if (item.symb() == null) continue;
            var resolved = KisResponseParser.resolvePrice(item.last(), item.base());
            if (resolved.isEmpty()) {
                log.warn("복수종목 스냅샷 응답 항목 누락(last·base 모두 빈값): symb={}", item.symb());
                continue;
            }
            if (item.last() == null || item.last().isBlank()) {
                log.info("복수종목 스냅샷 — last 빈값, base 사용: symb={}, base={}", item.symb(), item.base());
            }
            Ticker ticker = Ticker.tryParse(item.symb()).orElse(null);
            if (ticker == null) {
                log.warn("복수종목 스냅샷 응답 — Ticker 매핑 실패(무시): symb={}", item.symb());
                continue;
            }
            BigDecimal current = resolved.get();
            BigDecimal prevClose = Optional.ofNullable(item.base()).filter(s -> !s.isBlank())
                    .map(KisResponseParser::parseBd)
                    .orElse(current);
            result.put(ticker, new PriceSnapshot(current, prevClose));
        }

        // 복수종목 응답에 없는 종목 → 단건 API fallback
        for (Ticker ticker : tickers) {
            if (!result.containsKey(ticker)) {
                log.warn("복수종목 스냅샷 응답 누락 — 단건 API fallback 시도: ticker={}", ticker);
                try {
                    PriceSnapshot snapshot = getPriceSnapshot(ticker, account);
                    result.put(ticker, snapshot);
                    log.info("단건 API fallback 성공: ticker={}, price={}", ticker, snapshot.current());
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
