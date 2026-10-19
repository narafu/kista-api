package com.kista.tradingcore.stats.application.port.output;

import com.kista.tradingcore.stats.domain.model.DatedValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// 코인베이스 공개 시세 조회 포트 (USD 현물 마켓 — asset은 BTC·ETH·USDT 등 기초자산 코드)
public interface CoinbaseMarketDataPort {

    // <asset>-USD 현재가
    BigDecimal getUsdPrice(String asset);

    // from(UTC 일자) 이후 <asset>-USD 일봉 종가 — 300봉 초과는 페이징, 진행 중 봉 포함, 오래된 순
    List<DatedValue> getUsdDailyClosesSince(String asset, LocalDate from);
}
