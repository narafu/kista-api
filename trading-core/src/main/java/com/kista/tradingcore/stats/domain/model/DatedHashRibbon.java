package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 일자별 Hash Ribbons 점 — 해시레이트 30·60일 이동평균과 채굴자 항복/회복 상태
public record DatedHashRibbon(
        LocalDate date, // 기준 일자
        BigDecimal hashrateEh, // 일 평균 해시레이트(EH/s)
        BigDecimal sma30, // 30일 이동평균 (이력 30일 미만이면 null)
        BigDecimal sma60, // 60일 이동평균 (이력 60일 미만이면 null)
        State state, // sma30 < sma60 이면 CAPITULATION, 아니면 RECOVERY (sma60 없으면 null)
        boolean buySignal // 전날 CAPITULATION → 당일 RECOVERY 교차일
) {
    public enum State { CAPITULATION, RECOVERY }
}
