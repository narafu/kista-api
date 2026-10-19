package com.kista.tradingcore.stats.application.port.output;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.domain.model.DatedLongShortRatio;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import com.kista.tradingcore.stats.domain.model.FundingRateQuote;
import com.kista.tradingcore.stats.domain.model.FundingRateRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 바이낸스 공개 시세 조회 포트 (현물 USDT 마켓 + USDT-M 선물)
public interface BinanceMarketDataPort {

    // 현물 현재가 (USDT)
    BigDecimal getSpotPrice(StrategyTicker ticker);

    // 현물 일봉 종가 — 진행 중 봉 포함, 오래된 순
    List<DatedValue> getSpotDailyCloses(StrategyTicker ticker, int limit);

    // from(UTC 일자) 이후 현물 일봉 종가 전체 — 1000개 초과는 페이징, 진행 중 봉 포함, 오래된 순
    List<DatedValue> getSpotDailyClosesSince(StrategyTicker ticker, LocalDate from);

    // 선물 현재 펀딩비율·다음 정산 시각
    FundingRateQuote getFundingRate(StrategyTicker ticker);

    // from 이후 펀딩비 정산 이력 (최대 1000건)
    List<FundingRateRecord> getFundingHistory(StrategyTicker ticker, Instant from);

    // 선물 일별 미결제약정 명목가(USD) 최근 30일
    List<DatedValue> getOpenInterestHistory(StrategyTicker ticker);

    // 선물 일별 롱/숏 계좌 비율 최근 30일 (바이낸스가 30일만 제공)
    List<DatedLongShortRatio> getLongShortAccountRatioHistory(StrategyTicker ticker);
}
