package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.stats.application.port.output.BinanceMarketDataPort;
import com.kista.tradingcore.stats.application.port.output.HistoricalFxRatePort;
import com.kista.tradingcore.stats.application.usecase.BrokerStatisticsUseCase;
import com.kista.tradingcore.stats.application.usecase.CryptoMarketIndicatorUseCase;
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

// 코인 시장 지표 조립 — 섹션별 실패 격리(null) + 10분 인메모리 캐시(전부 실패 결과는 1분, 장기 일봉은 다음 UTC 자정까지)
@Slf4j
@Service
class CryptoMarketIndicatorService implements CryptoMarketIndicatorUseCase {

    static final int MAX_DAYS = 200; // 조회 가능 최대 기간(일)
    static final Duration TTL = Duration.ofMinutes(10); // 캐시 유효 시간
    static final Duration EMPTY_TTL = Duration.ofMinutes(1); // 전부 실패 결과 캐시 — 비인증 공개라 장애 중 요청마다 외부 재호출되는 것을 막는다
    private static final String CACHE_KEY = "crypto-market-indicators"; // 전역 단일 결과
    private static final String EMPTY_CACHE_KEY = CACHE_KEY + ":empty"; // 전부 실패 결과 — 정상 결과와 TTL이 달라 키 분리
    // 장기 일봉 종목별 캐시 키 접두사 — 키에 날짜를 넣지 않는다(TtlResultCache는 1000건 이상일 때만 만료 청소라 날짜별 키가 쌓인다)
    private static final String DAILY_CLOSES_CACHE_KEY = CACHE_KEY + ":daily-closes:";
    private static final LocalDate DAILY_CLOSES_FROM = LocalDate.of(2017, 1, 1); // 바이낸스 개장 이전 — 상장 첫 봉(BTC·ETH 2017-08-17)부터 받아 ATH를 전 이력으로
    private static final int FX_MARGIN_DAYS = 7; // Frankfurter 창 앞 여유 — 주말·연휴 직전 영업일 환율 확보
    private static final List<StrategyTicker> TICKERS = Arrays.stream(StrategyTicker.values())
            .filter(t -> t.getMarket() == Market.CRYPTO_KRW).toList();

    private final CryptoExchangePort upbit; // 업비트 (국내 기준가)
    private final BinanceMarketDataPort binance; // 바이낸스 (해외 기준가·선물)
    private final HistoricalFxRatePort fxRates; // 과거 공식 환율
    private final BrokerStatisticsUseCase brokerStatistics; // 현재 공식 환율
    private final Clock clock; // 조립 시각·오늘 날짜 기준 — 테스트에서 고정
    private final TtlResultCache cache; // 결과 캐시 (single-flight, 정상 10분·전부 실패 1분)

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    CryptoMarketIndicatorService(CryptoExchanges exchanges, BinanceMarketDataPort binance, HistoricalFxRatePort fxRates,
                                 BrokerStatisticsUseCase brokerStatistics) {
        this(exchanges, binance, fxRates, brokerStatistics, Clock.systemUTC());
    }

    CryptoMarketIndicatorService(CryptoExchanges exchanges, BinanceMarketDataPort binance, HistoricalFxRatePort fxRates,
                                 BrokerStatisticsUseCase brokerStatistics, Clock clock) {
        this.cache = new TtlResultCache(clock);
        this.upbit = exchanges.of(Broker.UPBIT);
        this.binance = binance;
        this.fxRates = fxRates;
        this.brokerStatistics = brokerStatistics;
        this.clock = clock;
    }

    @Override
    public CryptoMarketIndicators get(int days) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("조회 기간은 1~200일입니다: " + days);
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
        List<CryptoCandle> usdtCandles;
        try {
            usdtPrice = upbit.getUsdtKrwPrice();
            usdtCandles = upbit.getCompletedUsdtKrwDailyCandles(MAX_DAYS);
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

        // 바이낸스 데이터는 UTC 일자 키가 스펙 — KST 단일 기준의 명시적 예외
        Instant fundingFrom = today.atStartOfDay().toInstant(ZoneOffset.UTC).minus(Duration.ofDays(MAX_DAYS));
        // 현재가는 전 종목 한 번에 조회 — 실패하면 김프 섹션만 null
        Map<StrategyTicker, BigDecimal> krwPrices = upbitOk ? guard("업비트 현재가", () -> upbit.getCurrentPrices(TICKERS)) : null;
        List<TickerIndicators> tickers = new ArrayList<>();
        for (StrategyTicker t : TICKERS) {
            KimchiPremium kimchi = null;
            if (upbitOk && krwPrices != null) {
                final BigDecimal tetherPrice = usdtPrice;
                final List<CryptoCandle> usdtC = usdtCandles;
                final BigDecimal fxNow = fxCurrent;
                final Map<LocalDate, BigDecimal> fxMap = fxByDate;
                kimchi = guard("김프 " + t, () -> kimchi(t, krwPrices.get(t), tetherPrice, usdtC, fxNow, fxMap));
            }
            FundingRate funding = guard("펀딩비 " + t, () -> {
                FundingRateQuote q = binance.getFundingRate(t);
                return new FundingRate(q.rate(), q.nextFundingTime(),
                        CryptoPremiums.fundingDailyAverage(binance.getFundingHistory(t, fundingFrom)));
            });
            OpenInterest oi = guard("OI " + t, () -> new OpenInterest(binance.getOpenInterestHistory(t)));
            Valuation valuation = guard("밸류에이션 " + t,
                    () -> Valuation.of(CryptoValuations.history(dailyCloses(t, now), today, MAX_DAYS)));
            LongShortRatio longShort = guard("롱숏 비율 " + t, () -> LongShortRatio.of(binance.getLongShortAccountRatioHistory(t)));
            tickers.add(new TickerIndicators(t, kimchi, funding, oi, valuation, longShort));
        }
        return new CryptoMarketIndicators(now, usdtPremium, List.copyOf(tickers));
    }

    private KimchiPremium kimchi(StrategyTicker t, BigDecimal krw, BigDecimal usdtPrice, List<CryptoCandle> usdtCandles,
                                 BigDecimal fxCurrent, Map<LocalDate, BigDecimal> fxByDate) {
        BigDecimal spot = binance.getSpotPrice(t);
        // 환율 장애면 official 없이 tether 기준만
        BigDecimal official = fxCurrent == null ? null : CryptoPremiums.premiumPercent(krw, spot.multiply(fxCurrent));
        BigDecimal tether = CryptoPremiums.premiumPercent(krw, spot.multiply(usdtPrice));
        List<DatedPremiumPair> history = CryptoPremiums.kimchiHistory(
                upbit.getCompletedDailyCandles(t, MAX_DAYS), usdtCandles,
                binance.getSpotDailyCloses(t, MAX_DAYS + 1), fxByDate);
        return new KimchiPremium(new PremiumPair(official, tether), history);
    }

    // 상장 이후 전체 일봉(종목당 약 3,400봉, 페이지 4회)은 다음 UTC 자정까지 따로 캐시 — 완성 봉은 하루 한 번만 늘어나
    // 10분마다 페이징 재호출할 이유가 없다. 진행 중 봉은 계산에서 빼므로 그 사이 갱신은 무관. 실패(예외)는 캐시되지 않는다
    private List<DatedValue> dailyCloses(StrategyTicker t, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant nextUtcMidnight = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        // 만료 시각은 로더 종료 시점 + TTL이라 자정을 몇 초 넘길 수 있다 — 조회 시점 진행 중 봉을 캐시에 남기지 않아
        // 자정 직후 그 미완성 봉이 '어제 완성 봉'으로 오인되지 않게 한다(그 몇 초는 하루 늦은 값)
        return cache.getOrCompute(DAILY_CLOSES_CACHE_KEY + t, Duration.between(now, nextUtcMidnight),
                () -> binance.getSpotDailyClosesSince(t, DAILY_CLOSES_FROM).stream()
                        .filter(c -> c.date().isBefore(today)).toList());
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
