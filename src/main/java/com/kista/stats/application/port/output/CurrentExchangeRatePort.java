package com.kista.stats.application.port.output;

import java.math.BigDecimal;

// 현재 USD/KRW 매매기준율(TOSS_INVEST) 조회 — StatsService의 자산곡선 벤치마크 비교용.
// broker.domain.model.toss.TossExchangeRate(trading-core) 중 midRate 필드 하나만 필요해
// 포트 자체를 좁게 정의(narrowing) — StatsService는 rate() 필드를 쓰지 않는다.
public interface CurrentExchangeRatePort {
    BigDecimal getMidRate(); // 조회 실패 시 null
}
