package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.common.UsTradeDates;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    private static final String DAILY_PATH  = "/uapi/overseas-price/v1/quotations/dailyprice";
    private static final String DAILY_TR_ID = "HHDFS76240000";

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

    // KIS는 base(전일종가)가 현재가 응답에 묶여 있어 별도 API 없음 — snapshot 재사용 (호출 횟수 절감 없음)
    public BigDecimal getPrevClose(Ticker ticker, Account account) {
        return getPriceSnapshot(ticker, account).prevClose();
    }

    public Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account) {
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        getPriceSnapshots(tickers, account).forEach((ticker, snapshot) -> result.put(ticker, snapshot.prevClose()));
        return result;
    }

    // 정규장 확정 종가 — 마감 리포트 전용(dailyprice, HHDFS76240000). 응답 봉 날짜가 기대 거래일과 다르면
    // (미발행 등) 라이브 현재가로 fallback — "하루 전 종가를 오늘 종가로 오기록"하는 사고를 방지한다.
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return fetchConfirmedClose(ticker, tradeDate, account).orElseGet(() -> getPrice(ticker, account));
    }

    // dailyprice는 종목당 단건 TR이라 벌크 API 없음 — 종목 수만큼 순차 호출(마감 리포트 1일 1회라 허용)
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        for (Ticker ticker : tickers) {
            result.put(ticker, getClosingPrice(ticker, tradeDate, account));
        }
        return result;
    }

    // dailyprice 확정 종가 조회 — 응답 봉 날짜(xymd)가 기대 US 거래일과 일치할 때만 신뢰
    private Optional<BigDecimal> fetchConfirmedClose(Ticker ticker, LocalDate tradeDate, Account account) {
        String expectedUsDate = UsTradeDates.toUsTradeDate(tradeDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            DailyPriceResponse response = kisHttpClient.pricingGet(
                    DAILY_TR_ID, DAILY_PATH, account, DailyPriceResponse.class,
                    p -> {
                        p.add("EXCD", exchangeRegistry.excd(ticker));
                        p.add("SYMB", ticker.name());
                        p.add("GUBN", "0");
                        p.add("BYMD", expectedUsDate);
                        p.add("MODP", "0");
                    });
            if (response == null || response.output2() == null || response.output2().isEmpty()) {
                log.warn("확정 종가(dailyprice) 응답 없음 — 라이브 현재가로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            DailyPriceResponse.Output2 bar = response.output2().get(0);
            if (bar.clos() == null || bar.clos().isBlank()) {
                log.warn("확정 종가(dailyprice) 종가 빈값 — 라이브 현재가로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            if (bar.xymd() == null || !bar.xymd().equals(expectedUsDate)) {
                log.warn("확정 종가(dailyprice) 봉 날짜 불일치(기대={}, 응답={}) — 아직 미발행으로 보고 라이브 현재가로 fallback: ticker={}",
                        expectedUsDate, bar.xymd(), ticker);
                return Optional.empty();
            }
            return Optional.of(KisResponseParser.parseBd(bar.clos()));
        } catch (Exception e) {
            log.warn("확정 종가(dailyprice) 조회 실패 — 라이브 현재가로 fallback: ticker={}", ticker, e);
            return Optional.empty();
        }
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

    // 해외주식 기간별시세(일봉) 응답 — GUBN=0(일) + BYMD 기준 output2[0]이 해당 날짜의 봉.
    // xymd(영업일자, YYYYMMDD)는 KIS 공식 문서 기준 추정 필드명 — 실응답 검증 필요(위 주의사항 참고)
    record DailyPriceResponse(@JsonProperty("output2") List<Output2> output2) {
        record Output2(
            @JsonProperty("xymd") String xymd, // 영업일자 (YYYYMMDD)
            @JsonProperty("clos") String clos  // 종가
        ) {}
    }
}
