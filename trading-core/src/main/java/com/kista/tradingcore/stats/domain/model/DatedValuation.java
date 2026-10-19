package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 일자별 장기 밸류에이션 — 그날까지의 완성 일봉 종가로만 계산
public record DatedValuation(
        LocalDate date, // 기준 일자 (바이낸스 UTC 일자)
        BigDecimal close, // 그날 종가 (USDT)
        BigDecimal wma200, // 200주 이동평균(1400일 단순평균) — 이력 1400일 미만이면 null
        BigDecimal wma200Multiple, // 종가 / 200주 이동평균 — wma200이 null이면 null
        BigDecimal mayerMultiple, // 종가 / 200일 이동평균 — 이력 200일 미만이면 null
        BigDecimal athDrawdownPercent // 그날까지 최고 종가 대비 하락률 % (0 이하)
) {}
