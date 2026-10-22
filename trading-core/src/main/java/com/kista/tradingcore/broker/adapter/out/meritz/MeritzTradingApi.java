package com.kista.tradingcore.broker.adapter.out.meritz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.platform.time.UsTradeDates;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerBalance;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.broker.domain.model.MarginItem;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.tradingcore.broker.domain.model.SellableQuantity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 메리츠 해외주식 계좌 조회 — 잔고·주문가능금액·증거금·판매가능수량·보유현황·체결 내역
@Component
@RequiredArgsConstructor
class MeritzTradingApi {

    private static final String HOLDINGS_PATH = "/accounts/v1/me/overseas/holdings";
    private static final String CURRENCIES_PATH = "/accounts/v1/me/overseas/currencies";
    private static final String ESTIMATE_PATH = "/trading/v1/overseas/orders/estimate";
    private static final String HISTORY_PATH = "/trading/v1/overseas/orders/history";

    private final MeritzHttpClient http;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingRow(
            @JsonProperty("ovrs_stck_symb_iscd") String symbol,   // 종목코드(접미사 없는 심볼)
            @JsonProperty("rmnd_dqty") String holdings,           // 잔고수량 — "417.0000000000" 소수 표기
            @JsonProperty("seln_able_dqty") String sellable,      // 판매가능수량
            @JsonProperty("avrg_unpr") String avgPrice,           // 평균단가
            @JsonProperty("psnt_prc") String currentPrice) {}     // 현재가

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CurrencyRow(
            @JsonProperty("crcd") String currency,
            @JsonProperty("frcr_amt") String deposit,             // 외화 예수금
            @JsonProperty("vltn_exrt") String rate) {}            // 평가환율

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EstimateRow(
            @JsonProperty("crcd") String currency,
            @JsonProperty("exrt") String rate,
            @JsonProperty("stnd_deps_camt") String deposit,       // 기준 예수금
            @JsonProperty("oder_crnc_oder_able_camt") String orderable) {} // 주문통화 주문가능금액

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryRow(
            @JsonProperty("oder_date") String orderDate,          // US 거래일 yyyyMMdd
            @JsonProperty("oder_no") String orderNo,
            @JsonProperty("seln_byov_clcd") String side,          // 1 매도 / 2 매수
            @JsonProperty("iscd") String symbol,                  // 접미사 포함(SOXL.AX)
            @JsonProperty("oder_rctf_cncl_cls_code") String kind, // 0 원주문 / 1 정정 / 2 취소
            @JsonProperty("cntg_dqty") String filledQty,
            @JsonProperty("cntg_crnc_prc") String filledPrice,
            @JsonProperty("cntg_camt") String filledAmount) {}

    BrokerBalance getBalance(BrokerAccountRef account, StrategyTicker ticker) {
        HoldingRow row = findHolding(account, ticker);
        int qty = row == null ? 0 : MeritzResponse.wholeQuantity(row.holdings());
        BigDecimal avg = qty == 0 ? null : MeritzResponse.decimal(row.avgPrice());
        return new BrokerBalance(qty, avg, getUsdBuyableAmount(account));
    }

    BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        EstimateRow usd = estimateRow(account, "USD");
        BigDecimal able = usd == null ? null : MeritzResponse.decimal(usd.orderable());
        return able == null ? BigDecimal.ZERO : able;
    }

    List<MarginItem> getMargin(BrokerAccountRef account) {
        EstimateRow usd = estimateRow(account, "USD");
        BigDecimal able = orZero(usd == null ? null : usd.orderable());
        return List.of(new MarginItem(Currency.USD, able, able, able, orZero(usd == null ? null : usd.rate())));
    }

    SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        HoldingRow row = findHolding(account, ticker);
        return new SellableQuantity(ticker.name(), row == null ? 0 : MeritzResponse.wholeQuantity(row.sellable()));
    }

    PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        List<PresentBalanceResult.TossHolding> holdings = new ArrayList<>();
        for (HoldingRow h : holdings(account)) {
            Optional<StrategyTicker> ticker = StrategyTicker.tryParse(h.symbol());
            int qty = MeritzResponse.wholeQuantity(h.holdings());
            if (ticker.isEmpty() || qty == 0) continue; // 전략 종목 밖·0주는 제외
            holdings.add(new PresentBalanceResult.TossHolding(ticker.get(), qty,
                    MeritzResponse.decimal(h.avgPrice()), MeritzResponse.decimal(h.currentPrice())));
        }
        CurrencyRow usd = http.rowList(http.get(CURRENCIES_PATH, account, new LinkedMultiValueMap<>()), CurrencyRow.class)
                .stream().filter(c -> "USD".equals(c.currency())).findFirst().orElse(null);
        EstimateRow krw = estimateRow(account, "KRW");
        return PresentBalanceResult.aggregateToss(holdings,
                orZero(usd == null ? null : usd.deposit()),
                orZero(krw == null ? null : krw.deposit()),
                orZero(usd == null ? null : usd.rate()));
    }

    // cntg_cls_code=0(전체) 조회 후 체결수량>0만 — 체결만(1)이 부분체결 후 만료 건을 주는지 미실측
    List<Execution> getExecutions(LocalDate kstFrom, LocalDate kstTo, StrategyTicker ticker, BrokerAccountRef account) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("oder_sta_date", UsTradeDates.toUsTradeDate(kstFrom).format(DateTimeFormatter.BASIC_ISO_DATE));
        p.add("oder_fin_date", UsTradeDates.toUsTradeDate(kstTo).format(DateTimeFormatter.BASIC_ISO_DATE));
        p.add("cntg_cls_code", "0");
        List<Execution> result = new ArrayList<>();
        for (HistoryRow r : http.getAllPages(HISTORY_PATH, account, p, HistoryRow.class)) {
            if (!"0".equals(r.kind()) || !ticker.name().equals(baseSymbol(r.symbol()))) continue; // 원주문·해당 종목만
            int qty = MeritzResponse.wholeQuantity(r.filledQty());
            if (qty <= 0) continue;
            LocalDate usDate = LocalDate.parse(r.orderDate(), DateTimeFormatter.BASIC_ISO_DATE);
            result.add(new Execution(UsTradeDates.toKstTradeDate(usDate), ticker,
                    "1".equals(r.side()) ? OrderDirection.SELL : OrderDirection.BUY, qty,
                    MeritzResponse.decimal(r.filledPrice()), MeritzResponse.decimal(r.filledAmount()), r.orderNo()));
        }
        return result;
    }

    private List<HoldingRow> holdings(BrokerAccountRef account) {
        return http.getAllPages(HOLDINGS_PATH, account, new LinkedMultiValueMap<>(), HoldingRow.class);
    }

    // 내역 행 심볼은 접미사 포함(SOXL.AX) — 첫 점 앞만 비교
    private static String baseSymbol(String iscd) {
        if (iscd == null) return null;
        int dot = iscd.indexOf('.');
        return dot < 0 ? iscd : iscd.substring(0, dot);
    }

    private HoldingRow findHolding(BrokerAccountRef account, StrategyTicker ticker) {
        return holdings(account).stream().filter(h -> ticker.name().equals(h.symbol())).findFirst().orElse(null);
    }

    private EstimateRow estimateRow(BrokerAccountRef account, String currency) {
        return http.rowList(http.get(ESTIMATE_PATH, account, new LinkedMultiValueMap<>()), EstimateRow.class)
                .stream().filter(e -> currency.equals(e.currency())).findFirst().orElse(null);
    }

    private static BigDecimal orZero(String s) {
        BigDecimal v = MeritzResponse.decimal(s);
        return v == null ? BigDecimal.ZERO : v;
    }
}
