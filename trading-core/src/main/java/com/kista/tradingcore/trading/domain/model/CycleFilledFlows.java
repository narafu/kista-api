package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.TreeMap;

// 사이클의 일자(KST 거래일)별 누적 체결금액 — buys = 누적 BUY 체결금액(매수원가), sells = 누적 SELL 체결금액(인출된 매도대금)
public record CycleFilledFlows(NavigableMap<LocalDate, BigDecimal> buys, NavigableMap<LocalDate, BigDecimal> sells) {

    // 일자별 누적 순투입액 = 누적 매수 − 누적 매도 (체결이 있는 거래일마다 두 맵이 같은 키를 갖는다)
    public NavigableMap<LocalDate, BigDecimal> netInvested() {
        NavigableMap<LocalDate, BigDecimal> net = new TreeMap<>();
        buys.forEach((date, buy) -> net.put(date, buy.subtract(sells.getOrDefault(date, BigDecimal.ZERO))));
        return net;
    }
}
