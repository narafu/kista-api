package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Market;
import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.shared.kernel.StrategyTicker;
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
import com.kista.tradingcore.stats.domain.model.DatedPremiumPair;
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

// 코인 시장 지표 조립 — 섹션별 실패 격리(null) + 10분 인메모리 캐시
@Slf4j
@Service
class CryptoMarketIndicatorService implements CryptoMarketIndicatorUseCase {

    static final int MAX_DAYS = 200; // 조회 가능 최대 기간(일)
    static final Duration TTL = Duration.ofMinutes(10); // 캐시 유효 시간
    private static final int FX_MARGIN_DAYS = 7; // Frankfurter 창 앞 여유 — 주말·연휴 직전 영업일 환율 확보
    private static final List<StrategyTicker> TICKERS = Arrays.stream(StrategyTicker.values())
            .filter(t -> t.getMarket() == Market.CRYPTO_KRW).toList();

    private final CryptoExchangePort upbit; // 업비트 (국내 기준가)
    private final BinanceMarketDataPort binance; // 바이낸스 (해외 기준가·선물)
    private final HistoricalFxRatePort fxRates; // 과거 공식 환율
    private final BrokerStatisticsUseCase brokerStatistics; // 현재 공식 환율
    private final Clock clock; // 조립 시각·오늘 날짜 기준 — 테스트에서 고정
    private final TtlResultCache cache; // 10분 결과 캐시 (single-flight, null 미캐시)

    private static final String CACHE_KEY = "crypto-market-indicators"; // 전역 단일 결과

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
        // 전부 실패한 결과는 캐시하지 않는다(loader가 null) — 다음 요청이 재조회, 응답은 빈 결과 그대로
        CryptoMarketIndicators[] assembled = new CryptoMarketIndicators[1];
        CryptoMarketIndicators result = cache.getOrCompute(CACHE_KEY, TTL, () -> {
            assembled[0] = assemble();
            return assembled[0].isEmpty() ? null : assembled[0];
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
            tickers.add(new TickerIndicators(t, kimchi, funding, oi));
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
