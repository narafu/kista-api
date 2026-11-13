package com.kista.tradingcore.broker.adapter.out.meritz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.platform.time.UsTradeDates;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.broker.domain.model.MarginItem;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.tradingcore.broker.domain.model.SellableQuantity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 메리츠 해외주식 계좌 조회 — 잔고·주문가능금액·증거금·판매가능수량·보유현황·체결 내역
@Component
@RequiredArgsConstructor
class MeritzTradingApi {

    private static final String HOLDINGS_PATH = "/accounts/v1/me/overseas/holdings";
    private static final String CURRENCIES_PATH = "/accounts/v1/me/overseas/currencies";
    private static final String ESTIMATE_PATH = "/trading/v1/overseas/orders/estimate";
    private static final String HISTORY_PATH = "/trading/v1/overseas/orders/history";

    private static final int AVG_PRICE_SCALE = 6; // 평균단가 자릿수 — 스펙 avrg_unpr 소수 6자리
    private final MeritzHttpClient http;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingRow(
            @JsonProperty("rmnd_clcd") String balanceClass,       // 잔고구분 1 일반 / 2 소수 / 3 대출 — 같은 종목이 구분별 행으로 온다
            @JsonProperty("ovrs_stck_symb_iscd") String symbol,   // 종목코드(접미사 없는 심볼)
            @JsonProperty("rmnd_dqty") String holdings,           // 잔고수량 — "417.0000000000" 소수 표기
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

    // 메리츠는 원화를 주문 시 자동 환전해 체결한다 — 각 통화 행의 oder_crnc_oder_able_camt가 이미 USD 환산값이라
    // 전 행 합이 실제 매수 가능 USD (2026-10-30 실측: USD 행 4501.94 + KRW 행 3577.99 = KRW 예수금 95% ÷ 환율)
    BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        return totalOrderable(estimateRows(account));
    }

    List<MarginItem> getMargin(BrokerAccountRef account) {
        List<EstimateRow> rows = estimateRows(account);
        EstimateRow usd = findCurrency(rows, "USD");
        BigDecimal total = totalOrderable(rows);
        return List.of(new MarginItem(Currency.USD, total, orZero(usd == null ? null : usd.orderable()), total,
                orZero(usd == null ? null : usd.rate())));
    }

    // 포트 계약(미체결 매도 미차감 보유 수량)에 맞춰 잔고수량 rmnd_dqty를 쓴다 — seln_able_dqty는 미체결 매도를 이미 뺀 순수량이라
    // 소비처가 DB PLANNED/PLACED SELL 예약을 다시 빼면 이중 차감된다(Toss 0d74f846과 같은 이유)
    // 정수 주문으로 팔 수 있는 일반 잔고 행만 센다(소수·대출 잔고는 별도 주문 경로라 포함하면 수량 부족 거절 위험)
    SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        BigDecimal general = holdings(account).stream()
                .filter(h -> ticker.name().equals(h.symbol()) && isGeneral(h))
                .map(h -> orZero(h.holdings()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SellableQuantity(ticker.name(), wholeOf(general));
    }

    PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        // 종목별로 잔고구분 행을 합친다 — 수량은 합산 후 정수 내림, 평단은 수량 가중 평균
        Map<StrategyTicker, List<HoldingRow>> bySymbol = new LinkedHashMap<>();
        for (HoldingRow h : holdings(account)) {
            StrategyTicker.tryParse(h.symbol()).ifPresent(t -> bySymbol.computeIfAbsent(t, _ -> new ArrayList<>()).add(h));
        }
        List<PresentBalanceResult.TossHolding> holdings = new ArrayList<>();
        bySymbol.forEach((ticker, rows) -> {
            BigDecimal qty = rows.stream().map(h -> orZero(h.holdings())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (wholeOf(qty) == 0) return; // 전략 종목 밖·1주 미만은 제외
            BigDecimal cost = rows.stream().map(h -> orZero(h.holdings()).multiply(orZero(h.avgPrice())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            holdings.add(new PresentBalanceResult.TossHolding(ticker, wholeOf(qty),
                    cost.divide(qty, AVG_PRICE_SCALE, RoundingMode.HALF_UP), rows.stream()
                    .map(h -> MeritzResponse.decimal(h.currentPrice())).filter(Objects::nonNull).findFirst().orElse(null)));
        });
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

    // 잔고구분 누락은 일반으로 본다
    private static boolean isGeneral(HoldingRow h) {
        return h.balanceClass() == null || h.balanceClass().isBlank() || "1".equals(h.balanceClass().trim());
    }

    private static int wholeOf(BigDecimal qty) {
        return qty.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private EstimateRow estimateRow(BrokerAccountRef account, String currency) {
        return findCurrency(estimateRows(account), currency);
    }

    private List<EstimateRow> estimateRows(BrokerAccountRef account) {
        return http.rowList(http.get(ESTIMATE_PATH, account, new LinkedMultiValueMap<>()), EstimateRow.class);
    }

    private static EstimateRow findCurrency(List<EstimateRow> rows, String currency) {
        return rows.stream().filter(e -> currency.equals(e.currency())).findFirst().orElse(null);
    }

    private static BigDecimal totalOrderable(List<EstimateRow> rows) {
        return rows.stream().map(e -> orZero(e.orderable())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal orZero(String s) {
        BigDecimal v = MeritzResponse.decimal(s);
        return v == null ? BigDecimal.ZERO : v;
    }
}
