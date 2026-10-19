package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// 코인 시장 지표 묶음 — 섹션은 개별 실패 시 null (부분 응답)
public record CryptoMarketIndicators(
        Instant asOf, // 조립 시각
        Premium usdtPremium, // 테더 프리미엄 (USDT 원화가 vs 공식 환율) — 실패 시 null
        List<TickerIndicators> tickers // 종목별 지표
) {
    // 테더 프리미엄
    public record Premium(BigDecimal current, List<DatedValue> history) {}

    // 김프 두 기준 (official은 환율 장애 시 null)
    public record PremiumPair(BigDecimal official, BigDecimal tether) {}

    public record KimchiPremium(PremiumPair current, List<DatedPremiumPair> history) {}

    public record FundingRate(BigDecimal current, Instant nextFundingTime, List<DatedValue> history) {}

    public record OpenInterest(List<DatedValue> history) {}

    // 종목별 지표 — 섹션 nullable
    public record TickerIndicators(StrategyTicker ticker, KimchiPremium kimchiPremium, FundingRate fundingRate, OpenInterest openInterest) {}

    // 김프·펀딩·테더 프리미엄 history를 마지막 days개로 자른다 (OI는 30일 고정이라 유지)
    public CryptoMarketIndicators withDays(int days) {
        return new CryptoMarketIndicators(asOf,
                usdtPremium == null ? null : new Premium(usdtPremium.current(), tail(usdtPremium.history(), days)),
                tickers.stream().map(t -> new TickerIndicators(t.ticker(),
                        t.kimchiPremium() == null ? null
                                : new KimchiPremium(t.kimchiPremium().current(), tail(t.kimchiPremium().history(), days)),
                        t.fundingRate() == null ? null
                                : new FundingRate(t.fundingRate().current(), t.fundingRate().nextFundingTime(), tail(t.fundingRate().history(), days)),
                        t.openInterest())).toList());
    }

    // usdtPremium과 모든 종목 섹션이 null이면 빈 결과 (캐시 제외 판정용)
    public boolean isEmpty() {
        return usdtPremium == null && tickers.stream().allMatch(t ->
                t.kimchiPremium() == null && t.fundingRate() == null && t.openInterest() == null);
    }

    private static <T> List<T> tail(List<T> list, int days) {
        return list.size() <= days ? list : list.subList(list.size() - days, list.size());
    }
}
