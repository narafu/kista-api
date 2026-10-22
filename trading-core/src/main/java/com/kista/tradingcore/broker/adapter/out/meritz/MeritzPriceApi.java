package com.kista.tradingcore.broker.adapter.out.meritz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.platform.time.UsTradeDates;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 메리츠 해외주식 시세 — 현재가·전일종가(prices), 확정 종가(candles/days)
@Slf4j
@Component
@RequiredArgsConstructor
class MeritzPriceApi {

    private static final String PRICE_PATH = "/market/v1/overseas/prices";
    private static final String CANDLE_PATH = "/market/v1/overseas/candles/days";

    private final MeritzHttpClient http;
    private final MeritzExchangeRegistry exchangeRegistry;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceRow(@JsonProperty("prpr") String prpr, @JsonProperty("prdy_clpr") String prdyClpr) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandleRow(@JsonProperty("date") String date, @JsonProperty("prpr") String prpr) {}

    BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account) {
        return getPriceSnapshot(ticker, account).current();
    }

    PriceSnapshot getPriceSnapshot(StrategyTicker ticker, BrokerAccountRef account) {
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
        BigDecimal prev = MeritzResponse.decimal(row.prdyClpr());
        return new PriceSnapshot(current, prev == null || prev.signum() <= 0 ? current : prev);
    }

    BigDecimal getPrevClose(StrategyTicker ticker, BrokerAccountRef account) {
        return getPriceSnapshot(ticker, account).prevClose();
    }

    // 정규장 확정 종가 — 봉 날짜가 기대 US 거래일과 정확히 같을 때만 신뢰, 아니면 라이브 현재가 fallback
    BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        BigDecimal confirmed = fetchConfirmedClose(ticker, tradeDate, account);
        if (confirmed != null) return confirmed;
        log.warn("확정 종가 없음 — 라이브 현재가로 fallback: ticker={}", ticker);
        return getPrice(ticker, account);
    }

    Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) result.put(t, getPrice(t, account));
        return result;
    }

    Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account) {
        Map<StrategyTicker, PriceSnapshot> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) result.put(t, getPriceSnapshot(t, account));
        return result;
    }

    Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) result.put(t, getPrevClose(t, account));
        return result;
    }

    Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) result.put(t, getClosingPrice(t, tradeDate, account));
        return result;
    }

    // 없음·불일치·예외는 null (호출부가 fallback)
    private BigDecimal fetchConfirmedClose(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        String expected = UsTradeDates.toUsTradeDate(tradeDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
            p.add("mrkt_div_code", "OV");
            p.add("mrkt_cls_code", exchangeRegistry.marketCode(ticker));
            p.add("iscd", ticker.name());
            p.add("mod_stpr_cls_code", "1"); // 수정주가 반영
            p.add("from", expected);
            p.add("to", expected);
            List<CandleRow> bars = http.rowList(http.get(CANDLE_PATH, account, p), CandleRow.class);
            if (bars.isEmpty()) {
                log.warn("확정 종가(candles) 응답 없음: ticker={}", ticker);
                return null;
            }
            CandleRow bar = bars.getFirst();
            BigDecimal close = MeritzResponse.decimal(bar.prpr());
            if (close == null || close.signum() <= 0) {
                log.warn("확정 종가(candles) 종가 빈값: ticker={}", ticker);
                return null;
            }
            if (!expected.equals(bar.date())) {
                log.warn("확정 종가(candles) 봉 날짜 불일치(기대={}, 응답={}): ticker={}", expected, bar.date(), ticker);
                return null;
            }
            return close;
        } catch (Exception e) {
            log.warn("확정 종가(candles) 조회 실패: ticker={}", ticker, e);
            return null;
        }
    }
}
