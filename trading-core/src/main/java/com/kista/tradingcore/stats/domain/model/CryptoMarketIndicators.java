package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// 코인 시장 지표 묶음 — 섹션은 개별 실패 시 null (부분 응답)
public record CryptoMarketIndicators(
        Instant asOf, // 조립 시각
        Premium usdtPremium, // 테더 프리미엄 (USDT 원화가 vs 공식 환율) — 실패 시 null
        List<TickerIndicators> tickers, // 종목별 지표
        Halving halving // BTC 반감기 카운트다운 — 실패 시 null
) {
    // BTC 반감기 카운트다운 — 예상 시각은 블록당 10분 가정
    public record Halving(long currentHeight, long nextHalvingHeight, long blocksRemaining, Instant estimatedAt) {
        static final long HALVING_INTERVAL = 210_000; // 반감기 주기(블록)
        static final Duration BLOCK_TIME = Duration.ofMinutes(10); // 목표 블록 생성 간격

        public static Halving of(long currentHeight, Instant asOf) {
            long next = (currentHeight / HALVING_INTERVAL + 1) * HALVING_INTERVAL;
            long remaining = next - currentHeight;
            return new Halving(currentHeight, next, remaining, asOf.plus(BLOCK_TIME.multipliedBy(remaining)));
        }
    }

    // 테더 프리미엄
    public record Premium(BigDecimal current, List<DatedValue> history) {}

    // 김프 두 기준 (official은 환율 장애 시 null)
    public record PremiumPair(BigDecimal official, BigDecimal tether) {}

    public record KimchiPremium(PremiumPair current, List<DatedPremiumPair> history) {}

    public record FundingRate(BigDecimal current, Instant nextFundingTime, List<DatedValue> history) {}

    public record OpenInterest(List<DatedValue> history) {}

    // 장기 밸류에이션 — current는 직전 완성 일봉 기준(history 마지막 점)
    public record Valuation(DatedValuation current, List<DatedValuation> history) {
        // 빈 이력은 실패로 본다 — non-null 섹션이 빈 history면 isEmpty 판정이 어긋난다
        public static Valuation of(List<DatedValuation> history) {
            if (history.isEmpty()) throw new IllegalStateException("밸류에이션 이력이 없습니다");
            return new Valuation(history.getLast(), history);
        }
    }

    // 선물 롱/숏 계좌 비율 — current는 history 마지막 점
    public record LongShortRatio(BigDecimal longAccount, BigDecimal longShortRatio, List<DatedLongShortRatio> history) {
        public static LongShortRatio of(List<DatedLongShortRatio> history) {
            if (history.isEmpty()) throw new IllegalStateException("롱/숏 비율 이력이 없습니다");
            return new LongShortRatio(history.getLast().longAccount(), history.getLast().longShortRatio(), history);
        }
    }

    // 종목별 지표 — 섹션 nullable
    public record TickerIndicators(StrategyTicker ticker, KimchiPremium kimchiPremium, FundingRate fundingRate, OpenInterest openInterest,
                                   Valuation valuation, LongShortRatio longShortRatio) {}

    // 김프·펀딩·밸류에이션·테더 프리미엄 history를 마지막 days개로 자른다 (OI·롱숏은 30일 고정이라 유지)
    public CryptoMarketIndicators withDays(int days) {
        return new CryptoMarketIndicators(asOf,
                usdtPremium == null ? null : new Premium(usdtPremium.current(), tail(usdtPremium.history(), days)),
                tickers.stream().map(t -> new TickerIndicators(t.ticker(),
                        t.kimchiPremium() == null ? null
                                : new KimchiPremium(t.kimchiPremium().current(), tail(t.kimchiPremium().history(), days)),
                        t.fundingRate() == null ? null
                                : new FundingRate(t.fundingRate().current(), t.fundingRate().nextFundingTime(), tail(t.fundingRate().history(), days)),
                        t.openInterest(),
                        t.valuation() == null ? null : new Valuation(t.valuation().current(), tail(t.valuation().history(), days)),
                        t.longShortRatio())).toList(),
                halving);
    }

    // usdtPremium·halving과 모든 종목 섹션이 null이면 빈 결과 (캐시 제외 판정용)
    public boolean isEmpty() {
        return usdtPremium == null && halving == null && tickers.stream().allMatch(t ->
                t.kimchiPremium() == null && t.fundingRate() == null && t.openInterest() == null
                        && t.valuation() == null && t.longShortRatio() == null);
    }

    private static <T> List<T> tail(List<T> list, int days) {
        return list.size() <= days ? list : list.subList(list.size() - days, list.size());
    }
}
