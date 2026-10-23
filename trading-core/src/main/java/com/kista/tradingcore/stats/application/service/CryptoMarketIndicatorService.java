package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.tradingcore.stats.application.port.output.BinanceMarketDataPort;
import com.kista.tradingcore.stats.application.port.output.BlockHeightPort;
import com.kista.tradingcore.stats.application.port.output.CoinbaseMarketDataPort;
import com.kista.tradingcore.stats.application.port.output.CryptoMarketMetricsPort;
import com.kista.tradingcore.stats.application.port.output.HashrateHistoryPort;
import com.kista.tradingcore.stats.application.port.output.HistoricalFxRatePort;
import com.kista.tradingcore.stats.application.port.output.UpbitMarketDataPort;
import com.kista.tradingcore.stats.application.usecase.BrokerStatisticsUseCase;
import com.kista.tradingcore.stats.application.usecase.CryptoMarketIndicatorUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoHashRibbons;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators.*;
import com.kista.tradingcore.stats.domain.model.CryptoPremiums;
import com.kista.tradingcore.stats.domain.model.CryptoValuations;
import com.kista.tradingcore.stats.domain.model.DatedPremiumPair;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import com.kista.tradingcore.stats.domain.model.FundingRateQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// 코인 시장 지표 조립 — 섹션별 실패 격리(null) + 10분 인메모리 캐시(전부 실패 결과는 1분, 장기 일봉·도미넌스/알트 시즌/해시레이트 이력은 다음 UTC 자정까지)
@Slf4j
@Service
class CryptoMarketIndicatorService implements CryptoMarketIndicatorUseCase {

    static final int MAX_DAYS = 365; // 조회 가능 최대 기간(일)
    static final Duration TTL = Duration.ofMinutes(10); // 캐시 유효 시간
    static final Duration EMPTY_TTL = Duration.ofMinutes(1); // 전부 실패 결과 캐시 — 비인증 공개라 장애 중 요청마다 외부 재호출되는 것을 막는다
    private static final String CACHE_KEY = "crypto-market-indicators"; // 전역 단일 결과
    private static final String EMPTY_CACHE_KEY = CACHE_KEY + ":empty"; // 전부 실패 결과 — 정상 결과와 TTL이 달라 키 분리
    // 장기 일봉 종목별 캐시 키 접두사 — 키에 날짜를 넣지 않는다(TtlResultCache는 1000건 이상일 때만 만료 청소라 날짜별 키가 쌓인다)
    private static final String DAILY_CLOSES_CACHE_KEY = CACHE_KEY + ":daily-closes:";
    private static final String DOMINANCE_HISTORY_CACHE_KEY = CACHE_KEY + ":btc-dominance-history"; // BTC 도미넌스 일별 이력 (자정 캐시)
    private static final String ALTCOIN_SEASON_HISTORY_CACHE_KEY = CACHE_KEY + ":altcoin-season-history"; // 알트 시즌 일별 이력 (자정 캐시)
    private static final String HASHRATES_CACHE_KEY = CACHE_KEY + ":hashrates"; // 일별 해시레이트 이력 (자정 캐시)
    private static final LocalDate DAILY_CLOSES_FROM = LocalDate.of(2017, 1, 1); // 바이낸스 개장 이전 — 상장 첫 봉(BTC·ETH 2017-08-17)부터 받아 ATH를 전 이력으로
    private static final int FX_MARGIN_DAYS = 7; // Frankfurter 창 앞 여유 — 주말·연휴 직전 영업일 환율 확보
    private static final String USDT = "USDT"; // 코인베이스 테더 보정용 자산 코드 (StrategyTicker에 넣지 않는다)
    private static final List<StrategyTicker> TICKERS = Arrays.stream(StrategyTicker.values())
            .filter(t -> t.tradableOn(Market.CRYPTO_KRW)).toList(); // 업비트 KRW 참조 시세 대상

    private final UpbitMarketDataPort upbit; // 업비트 공개 시세 (국내 기준가 — 김프)
    private final BinanceMarketDataPort binance; // 바이낸스 (해외 기준가·선물)
    private final HistoricalFxRatePort fxRates; // 과거 공식 환율
    private final BlockHeightPort blockHeight; // BTC 블록 높이 (반감기)
    private final CoinbaseMarketDataPort coinbase; // 코인베이스 (미국 현물 기준가)
    private final BrokerStatisticsUseCase brokerStatistics; // 현재 공식 환율
    private final CryptoMarketMetricsPort cmc; // CoinMarketCap (BTC 도미넌스·알트 시즌)
    private final HashrateHistoryPort hashrates; // BTC 일별 해시레이트 (Hash Ribbons)
    private final Clock clock; // 조립 시각·오늘 날짜 기준 — 테스트에서 고정
    private final TtlResultCache cache; // 결과 캐시 (single-flight, 정상 10분·전부 실패 1분)

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    CryptoMarketIndicatorService(UpbitMarketDataPort upbit, BinanceMarketDataPort binance, HistoricalFxRatePort fxRates,
                                 BlockHeightPort blockHeight, CoinbaseMarketDataPort coinbase, BrokerStatisticsUseCase brokerStatistics,
                                 CryptoMarketMetricsPort cmc, HashrateHistoryPort hashrates) {
        this(upbit, binance, fxRates, blockHeight, coinbase, brokerStatistics, cmc, hashrates, Clock.systemUTC());
    }

    CryptoMarketIndicatorService(UpbitMarketDataPort upbit, BinanceMarketDataPort binance, HistoricalFxRatePort fxRates,
                                 BlockHeightPort blockHeight, CoinbaseMarketDataPort coinbase, BrokerStatisticsUseCase brokerStatistics,
                                 CryptoMarketMetricsPort cmc, HashrateHistoryPort hashrates, Clock clock) {
        this.cache = new TtlResultCache(clock);
        this.upbit = upbit;
        this.binance = binance;
        this.fxRates = fxRates;
        this.blockHeight = blockHeight;
        this.coinbase = coinbase;
        this.brokerStatistics = brokerStatistics;
        this.cmc = cmc;
        this.hashrates = hashrates;
        this.clock = clock;
    }

    @Override
    public CryptoMarketIndicators get(int days) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("조회 기간은 1~" + MAX_DAYS + "일입니다: " + days);
        // 전부 실패한 결과는 정상 키에 두지 않고(loader가 null) 별도 키에 1분만 둔다 — 그동안은 빈 결과 재사용, 이후 재조회
        CryptoMarketIndicators[] assembled = new CryptoMarketIndicators[1];
        CryptoMarketIndicators result = cache.getOrCompute(CACHE_KEY, TTL, () -> {
            CryptoMarketIndicators recentEmpty = cache.peek(EMPTY_CACHE_KEY);
            assembled[0] = recentEmpty != null ? recentEmpty : assemble();
            if (!assembled[0].isEmpty()) return assembled[0];
            if (recentEmpty == null) cache.getOrCompute(EMPTY_CACHE_KEY, EMPTY_TTL, () -> assembled[0]);
            return null;
        });
        return (result != null ? result : assembled[0]).withDays(days);
    }

    private CryptoMarketIndicators assemble() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

        // (a) 업비트 USDT 현재가·일봉
        BigDecimal usdtPrice;
        List<DatedValue> usdtCandles;
        try {
            usdtPrice = upbit.getUsdtKrwPrice();
            usdtCandles = upbit.getCompletedUsdtKrwDailyCloses(MAX_DAYS);
        } catch (Exception e) {
            log.warn("업비트 USDT 시세 조회 실패", e);
            usdtPrice = null;
            usdtCandles = null;
        }
        boolean upbitOk = usdtPrice != null;

        // (b) 공식 환율 현재값·과거(주말·휴일 채움)
        BigDecimal fxCurrent;
        Map<LocalDate, BigDecimal> fxByDate;
        try {
            BigDecimal current = brokerStatistics.currentExchangeRate().midRate();
            fxByDate = CryptoPremiums.fillForward(
                    fxRates.getUsdKrwRates(today.minusDays(MAX_DAYS + FX_MARGIN_DAYS), today),
                    today.minusDays(MAX_DAYS), today);
            fxCurrent = current;
        } catch (Exception e) {
            log.warn("공식 환율 조회 실패", e);
            fxCurrent = null;
            fxByDate = Map.of();
        }

        Premium usdtPremium = null;
        if (upbitOk && fxCurrent != null) {
            usdtPremium = new Premium(CryptoPremiums.premiumPercent(usdtPrice, fxCurrent),
                    CryptoPremiums.usdtPremiumHistory(usdtCandles, fxByDate));
        }

        // (c) 코인베이스 USDT-USD 현재가·일봉 — 실패하면 모든 종목의 코인베이스 프리미엄만 null
        BigDecimal cbUsdtPrice;
        List<DatedValue> cbUsdtCloses;
        try {
            cbUsdtPrice = coinbase.getUsdPrice(USDT);
            cbUsdtCloses = coinbase.getUsdDailyClosesSince(USDT, today.minusDays(MAX_DAYS));
        } catch (Exception e) {
            log.warn("코인베이스 USDT 시세 조회 실패", e);
            cbUsdtPrice = null;
            cbUsdtCloses = null;
        }

        // 바이낸스 데이터는 UTC 일자 키가 스펙 — KST 단일 기준의 명시적 예외
        Instant fundingFrom = today.atStartOfDay().toInstant(ZoneOffset.UTC).minus(Duration.ofDays(MAX_DAYS));
        // 현재가는 전 종목 한 번에 조회 — 실패하면 김프 섹션만 null
        Map<StrategyTicker, BigDecimal> krwPrices = upbitOk ? guard("업비트 현재가", () -> upbit.getKrwPrices(TICKERS)) : null;
        List<TickerIndicators> tickers = new ArrayList<>();
        List<DatedValue> btcCloses = null; // Hash Ribbons 가격 모멘텀용 — 실패하면 섹션 null
        for (StrategyTicker t : TICKERS) {
            // 장기 일봉은 김프 추이·밸류에이션이 공유 — 실패하면 두 섹션 모두 null (조립당 종목별 1회 조회)
            List<DatedValue> closes = guard("장기 일봉 " + t, () -> dailyCloses(t, now));
            if (t == StrategyTicker.BTC) btcCloses = closes;
            // 바이낸스 현물 현재가는 김프·코인베이스 프리미엄이 공유 — 실패하면 두 섹션 모두 null
            BigDecimal spot = guard("바이낸스 현재가 " + t, () -> binance.getSpotPrice(t));
            KimchiPremium kimchi = null;
            if (upbitOk && krwPrices != null && closes != null && spot != null) {
                final BigDecimal tetherPrice = usdtPrice;
                final List<DatedValue> usdtC = usdtCandles;
                final BigDecimal fxNow = fxCurrent;
                final Map<LocalDate, BigDecimal> fxMap = fxByDate;
                kimchi = guard("김프 " + t, () -> kimchi(t, spot, closes, krwPrices.get(t), tetherPrice, usdtC, fxNow, fxMap));
            }
            Premium coinbasePremium = null;
            if (cbUsdtPrice != null && closes != null && spot != null) {
                final BigDecimal usdtUsd = cbUsdtPrice;
                final List<DatedValue> usdtUsdCloses = cbUsdtCloses;
                coinbasePremium = guard("코인베이스 프리미엄 " + t, () -> new Premium(
                        CryptoPremiums.premiumPercent(coinbase.getUsdPrice(t.name()), spot.multiply(usdtUsd)),
                        CryptoPremiums.coinbasePremiumHistory(coinbase.getUsdDailyClosesSince(t.name(), today.minusDays(MAX_DAYS)), usdtUsdCloses, closes, today)));
            }
            FundingRate funding = guard("펀딩비 " + t, () -> {
                FundingRateQuote q = binance.getFundingRate(t);
                return new FundingRate(q.rate(), q.nextFundingTime(),
                        CryptoPremiums.fundingDailyAverage(binance.getFundingHistory(t, fundingFrom)));
            });
            OpenInterest oi = guard("OI " + t, () -> new OpenInterest(binance.getOpenInterestHistory(t)));
            Valuation valuation = closes == null ? null
                    : guard("밸류에이션 " + t, () -> Valuation.of(CryptoValuations.history(closes, today, MAX_DAYS)));
            LongShortRatio longShort = guard("롱숏 비율 " + t, () -> LongShortRatio.of(binance.getLongShortAccountRatioHistory(t)));
            tickers.add(new TickerIndicators(t, kimchi, funding, oi, valuation, longShort, coinbasePremium));
        }
        Halving halving = guard("반감기", () -> Halving.of(blockHeight.currentBtcHeight(), now));

        // 시장 전체 지표 — BTC 도미넌스: 오늘 점이 섞여도 MAX_DAYS개가 남게 하나 더 요청
        DailyIndicator btcDominance = guard("BTC 도미넌스", () -> new DailyIndicator(cmc.btcDominance(),
                beforeToday(untilUtcMidnight(DOMINANCE_HISTORY_CACHE_KEY, now, () -> cmc.btcDominanceDaily(MAX_DAYS + 1)), today)));
        // 알트 시즌: 현재값 실패면 섹션 null, 이력만 실패하면 현재값만 유지
        DailyIndicator altcoinSeason = null;
        BigDecimal altIndex = guard("알트 시즌 지수", cmc::altcoinSeasonIndex);
        if (altIndex != null) {
            List<DatedValue> altHistory;
            try {
                altHistory = beforeToday(untilUtcMidnight(ALTCOIN_SEASON_HISTORY_CACHE_KEY, now,
                        () -> cmc.altcoinSeasonDaily(today.minusDays(MAX_DAYS), today)), today);
            } catch (Exception e) {
                log.warn("알트 시즌 이력 조회 실패 — 현재값만", e);
                altHistory = List.of();
            }
            altcoinSeason = new DailyIndicator(altIndex, altHistory);
        }
        List<DatedValue> hashRibbonCloses = btcCloses;
        HashRibbons hashRibbons = hashRibbonCloses == null ? null : guard("Hash Ribbons", () -> HashRibbons.of(CryptoHashRibbons.history(
                untilUtcMidnight(HASHRATES_CACHE_KEY, now, hashrates::dailyHashratesEh), hashRibbonCloses, today, MAX_DAYS)));
        return new CryptoMarketIndicators(now, usdtPremium, List.copyOf(tickers), halving, btcDominance, altcoinSeason, hashRibbons);
    }

    // 오늘(진행 중) 점을 뺀 일별 이력
    private static List<DatedValue> beforeToday(List<DatedValue> values, LocalDate today) {
        return values.stream().filter(v -> v.date().isBefore(today)).toList();
    }

    private KimchiPremium kimchi(StrategyTicker t, BigDecimal spot, List<DatedValue> binanceCloses, BigDecimal krw, BigDecimal usdtPrice, List<DatedValue> usdtCandles,
                                 BigDecimal fxCurrent, Map<LocalDate, BigDecimal> fxByDate) {
        // 환율 장애면 official 없이 tether 기준만
        BigDecimal official = fxCurrent == null ? null : CryptoPremiums.premiumPercent(krw, spot.multiply(fxCurrent));
        BigDecimal tether = CryptoPremiums.premiumPercent(krw, spot.multiply(usdtPrice));
        List<DatedPremiumPair> history = CryptoPremiums.kimchiHistory(
                upbit.getCompletedKrwDailyCloses(t, MAX_DAYS), usdtCandles,
                binanceCloses, fxByDate); // 장기 일봉 — 날짜 교집합만 쓰므로 길이 무관
        return new KimchiPremium(new PremiumPair(official, tether), history);
    }

    // 상장 이후 전체 일봉(종목당 약 3,400봉, 페이지 4회)은 다음 UTC 자정까지 따로 캐시 — 완성 봉은 하루 한 번만 늘어나
    // 10분마다 페이징 재호출할 이유가 없다. 진행 중 봉은 김프 조인·밸류에이션 계산이 빼므로 그 사이 갱신은 무관. 실패(예외)는 캐시되지 않는다.
    // 만료는 로더 종료 + TTL이라 자정을 로더 실행 시간만큼 넘기지만, 이 캐시는 메인 조립 안에서만 읽히고 다음 조립은
    // 최소 1분(EMPTY_TTL) 뒤라 자정 직후 어제의 미완성 봉을 완성 봉으로 쓰는 경로는 없다
    private List<DatedValue> dailyCloses(StrategyTicker t, Instant now) {
        return untilUtcMidnight(DAILY_CLOSES_CACHE_KEY + t, now, () -> binance.getSpotDailyClosesSince(t, DAILY_CLOSES_FROM));
    }

    // 다음 UTC 자정까지 캐시 — 장기 일봉과 일별 이력이 공유, 실패(예외)는 캐시되지 않는다
    private <T> T untilUtcMidnight(String key, Instant now, Supplier<T> loader) {
        Instant nextUtcMidnight = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return cache.getOrCompute(key, Duration.between(now, nextUtcMidnight), loader);
    }

    // 섹션 실패 시 null + 경고 로그
    private static <T> T guard(String section, Supplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            log.warn("코인 지표 섹션 조회 실패 ({})", section, e);
            return null;
        }
    }
}
