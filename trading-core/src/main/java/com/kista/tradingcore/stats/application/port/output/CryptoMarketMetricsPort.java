package com.kista.tradingcore.stats.application.port.output;

import com.kista.tradingcore.stats.domain.model.DatedValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// 코인 시장 전체 지표(BTC 도미넌스·알트 시즌 지수) 조회 포트 — 이력은 UTC 날짜 오래된 순, 같은 날짜 여러 점이면 마지막 점
public interface CryptoMarketMetricsPort {

    // 현재 BTC 도미넌스(%)
    BigDecimal btcDominance();

    // 최근 count일 일별 BTC 도미넌스(%)
    List<DatedValue> btcDominanceDaily(int count);

    // 현재 알트 시즌 지수(0~100)
    BigDecimal altcoinSeasonIndex();

    // from~to(UTC 날짜, 양끝 포함) 일별 알트 시즌 지수
    List<DatedValue> altcoinSeasonDaily(LocalDate from, LocalDate to);
}
