package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;
import com.kista.tradingcore.stats.application.port.output.BinanceMarketDataPort;
import com.kista.tradingcore.stats.application.port.output.BlockHeightPort;
import com.kista.tradingcore.stats.application.port.output.CoinbaseMarketDataPort;
import com.kista.tradingcore.stats.application.port.output.HistoricalFxRatePort;
import com.kista.tradingcore.stats.application.usecase.BrokerStatisticsUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
import com.kista.tradingcore.stats.domain.model.DatedLongShortRatio;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import com.kista.tradingcore.stats.domain.model.FundingRateQuote;
import com.kista.tradingcore.stats.domain.model.FundingRateRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CryptoMarketIndicatorServiceTest {

    static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    CryptoExchangePort upbit = mock(CryptoExchangePort.class);
    BinanceMarketDataPort binance = mock(BinanceMarketDataPort.class);
    HistoricalFxRatePort fx = mock(HistoricalFxRatePort.class);
    BlockHeightPort blockHeight = mock(BlockHeightPort.class);
    CoinbaseMarketDataPort coinbase = mock(CoinbaseMarketDataPort.class);
    BrokerStatisticsUseCase brokerStats = mock(BrokerStatisticsUseCase.class);
    CryptoMarketIndicatorService service;
    Instant[] now = {NOW}; // 테스트용 가변 시각

    Clock movableClock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now[0]; }
    };

    @BeforeEach
    void setUp() {
        CryptoExchanges exchanges = mock(CryptoExchanges.class); // 생성자가 package-private라 mock으로 대체
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        service = new CryptoMarketIndicatorService(exchanges, binance, fx, blockHeight, coinbase, brokerStats,
                movableClock);
        stubAllOk();
    }

    // 어제까지 n일 일봉
    static List<CryptoCandle> candles(int n, String close) {
        List<CryptoCandle> list = new ArrayList<>();
        for (int i = n; i >= 1; i--) list.add(new CryptoCandle(TODAY.minusDays(i), new BigDecimal(close)));
        return list;
    }

    // 어제까지 n일 + 오늘(진행 중)
    static List<DatedValue> closes(int n, String close) {
        List<DatedValue> list = new ArrayList<>();
        for (int i = n; i >= 0; i--) list.add(new DatedValue(TODAY.minusDays(i), new BigDecimal(close)));
        return list;
    }

    void stubAllOk() {
        when(upbit.getUsdtKrwPrice()).thenReturn(new BigDecimal("1450"));
        when(upbit.getCompletedUsdtKrwDailyCandles(anyInt())).thenReturn(candles(200, "1450"));
        when(upbit.getCurrentPrices(anyCollection())).thenReturn(
                Map.of(StrategyTicker.BTC, new BigDecimal("150000000"), StrategyTicker.ETH, new BigDecimal("150000000")));
        when(upbit.getCompletedDailyCandles(any(), anyInt())).thenReturn(candles(200, "150000000"));
        when(brokerStats.currentExchangeRate()).thenReturn(new ExchangeRateQuote(new BigDecimal("1401"), new BigDecimal("1400")));
        when(fx.getUsdKrwRates(any(), any())).thenReturn(Map.of(TODAY.minusDays(210), new BigDecimal("1400")));
        when(binance.getSpotPrice(any())).thenReturn(new BigDecimal("100000"));
        when(binance.getFundingRate(any())).thenReturn(new FundingRateQuote(new BigDecimal("0.0001"), NOW.plusSeconds(3600)));
        when(binance.getFundingHistory(any(), any())).thenReturn(List.of(new FundingRateRecord(NOW.minusSeconds(86400), new BigDecimal("0.0001"))));
        when(binance.getOpenInterestHistory(any())).thenReturn(List.of(new DatedValue(TODAY.minusDays(1), new BigDecimal("5"))));
        when(binance.getSpotDailyClosesSince(any(), any())).thenReturn(closes(1500, "100000"));
        when(binance.getLongShortAccountRatioHistory(any())).thenReturn(
                List.of(new DatedLongShortRatio(TODAY.minusDays(1), new BigDecimal("0.6"), new BigDecimal("1.5"))));
        when(blockHeight.currentBtcHeight()).thenReturn(972_175L);
        when(coinbase.getUsdPrice("USDT")).thenReturn(new BigDecimal("0.999"));
        when(coinbase.getUsdDailyCloses("USDT")).thenReturn(closes(250, "0.999"));
        when(coinbase.getUsdPrice("BTC")).thenReturn(new BigDecimal("99900"));
        when(coinbase.getUsdPrice("ETH")).thenReturn(new BigDecimal("99900"));
        when(coinbase.getUsdDailyCloses("BTC")).thenReturn(closes(250, "99900"));
        when(coinbase.getUsdDailyCloses("ETH")).thenReturn(closes(250, "99900"));
    }

    // 전 섹션 실패 stub
    void stubAllFail() {
        when(upbit.getUsdtKrwPrice()).thenThrow(new RuntimeException("upbit down"));
        when(brokerStats.currentExchangeRate()).thenThrow(new RuntimeException("fx down"));
        when(binance.getFundingRate(any())).thenThrow(new RuntimeException("down"));
        when(binance.getOpenInterestHistory(any())).thenThrow(new RuntimeException("down"));
        when(binance.getSpotDailyClosesSince(any(), any())).thenThrow(new RuntimeException("down"));
        when(binance.getLongShortAccountRatioHistory(any())).thenThrow(new RuntimeException("down"));
        when(blockHeight.currentBtcHeight()).thenThrow(new RuntimeException("mempool down"));
        when(coinbase.getUsdPrice(any())).thenThrow(new RuntimeException("coinbase down"));
    }

    @Test
    void 정상_조회시_종목별_세_지표와_테더프리미엄을_채운다() {
        CryptoMarketIndicators r = service.get(200);

        assertThat(r.asOf()).isEqualTo(NOW);
        assertThat(r.usdtPremium().current()).isEqualByComparingTo("3.5714"); // 1450/1400-1
        assertThat(r.usdtPremium().history()).hasSize(200);
        assertThat(r.tickers()).hasSize(2);
        r.tickers().forEach(t -> {
            assertThat(t.kimchiPremium().current().official()).isNotNull();
            assertThat(t.kimchiPremium().current().tether()).isNotNull();
            // 진행 중인 오늘 봉은 history에 섞이지 않는다
            assertThat(t.kimchiPremium().history()).hasSize(200)
                    .noneMatch(p -> p.date().equals(TODAY));
            assertThat(t.fundingRate().current()).isEqualByComparingTo("0.0001");
            assertThat(t.openInterest().history()).hasSize(1);
            // 밸류에이션은 오늘 봉을 빼고 마지막 200일, current는 어제
            assertThat(t.valuation().history()).hasSize(200).noneMatch(v -> v.date().equals(TODAY));
            assertThat(t.valuation().current().date()).isEqualTo(TODAY.minusDays(1));
            assertThat(t.valuation().current().wma200Multiple()).isEqualByComparingTo("1");
            assertThat(t.valuation().current().mayerMultiple()).isEqualByComparingTo("1");
            assertThat(t.valuation().current().athDrawdownPercent()).isEqualByComparingTo("0");
            assertThat(t.longShortRatio().longAccount()).isEqualByComparingTo("0.6");
            assertThat(t.longShortRatio().longShortRatio()).isEqualByComparingTo("1.5");
            // 99900 / (100000 × 0.999) − 1 = 0
            assertThat(t.coinbasePremium().current()).isEqualByComparingTo("0");
            assertThat(t.coinbasePremium().history()).hasSize(200).noneMatch(p -> p.date().equals(TODAY));
        });
    }

    @Test
    void 코인베이스만_실패하면_코인베이스_프리미엄만_null() {
        when(coinbase.getUsdDailyCloses("BTC")).thenThrow(new RuntimeException("coinbase down"));

        CryptoMarketIndicators r = service.get(200);

        assertThat(r.tickers().get(0).coinbasePremium()).isNull(); // BTC
        assertThat(r.tickers().get(1).coinbasePremium()).isNotNull(); // ETH
        r.tickers().forEach(t -> assertThat(t.kimchiPremium()).isNotNull());
    }

    @Test
    void 코인베이스_USDT_실패면_전_종목_코인베이스_프리미엄만_null() {
        when(coinbase.getUsdPrice("USDT")).thenThrow(new RuntimeException("coinbase down"));

        CryptoMarketIndicators r = service.get(200);

        r.tickers().forEach(t -> {
            assertThat(t.coinbasePremium()).isNull();
            assertThat(t.kimchiPremium()).isNotNull();
        });
    }

    @Test
    void 코인베이스_프리미엄만_살아있어도_빈_결과가_아니다() {
        var onlyCoinbase = new CryptoMarketIndicators(NOW, null, List.of(new CryptoMarketIndicators.TickerIndicators(
                StrategyTicker.BTC, null, null, null, null, null,
                new CryptoMarketIndicators.Premium(BigDecimal.ZERO, List.of()))), null);

        assertThat(onlyCoinbase.isEmpty()).isFalse();
    }

    @Test
    void 반감기는_다음_210000_배수까지_남은_블록과_10분_가정_예상_시각을_계산한다() {
        CryptoMarketIndicators.Halving h = service.get(200).halving();

        assertThat(h.currentHeight()).isEqualTo(972_175L);
        assertThat(h.nextHalvingHeight()).isEqualTo(1_050_000L);
        assertThat(h.blocksRemaining()).isEqualTo(77_825L);
        assertThat(h.estimatedAt()).isEqualTo(NOW.plus(java.time.Duration.ofMinutes(10 * 77_825L)));
    }

    @Test
    void 반감기_높이에_정확히_도달하면_다음_주기를_가리킨다() {
        when(blockHeight.currentBtcHeight()).thenReturn(840_000L);

        assertThat(service.get(200).halving().nextHalvingHeight()).isEqualTo(1_050_000L);
    }

    @Test
    void 반감기만_살아있어도_빈_결과가_아니다() {
        stubAllFail();
        reset(blockHeight);
        when(blockHeight.currentBtcHeight()).thenReturn(972_175L);

        assertThat(service.get(200).isEmpty()).isFalse();
    }

    @Test
    void mempool만_실패하면_반감기만_null() {
        when(blockHeight.currentBtcHeight()).thenThrow(new RuntimeException("mempool down"));

        CryptoMarketIndicators r = service.get(200);

        assertThat(r.halving()).isNull();
        assertThat(r.usdtPremium()).isNotNull();
        r.tickers().forEach(t -> assertThat(t.valuation()).isNotNull());
    }

    @Test
    void 장기_일봉만_실패하면_이를_공유하는_밸류에이션과_김프만_null() {
        when(binance.getSpotDailyClosesSince(any(), any())).thenThrow(new RuntimeException("klines down"));

        CryptoMarketIndicators r = service.get(200);

        assertThat(r.usdtPremium()).isNotNull();
        r.tickers().forEach(t -> {
            assertThat(t.valuation()).isNull();
            assertThat(t.kimchiPremium()).isNull();
            assertThat(t.fundingRate()).isNotNull();
            assertThat(t.longShortRatio()).isNotNull();
        });
    }

    @Test
    void 김프와_밸류에이션은_장기_일봉을_종목당_한_번만_조회한다() {
        service.get(200);

        verify(binance, times(1)).getSpotDailyClosesSince(org.mockito.ArgumentMatchers.eq(StrategyTicker.BTC), any());
        verify(binance, times(1)).getSpotDailyClosesSince(org.mockito.ArgumentMatchers.eq(StrategyTicker.ETH), any());
    }

    @Test
    void 롱숏_이력이_비면_롱숏만_null() {
        when(binance.getLongShortAccountRatioHistory(any())).thenReturn(List.of());

        CryptoMarketIndicators r = service.get(200);

        r.tickers().forEach(t -> {
            assertThat(t.longShortRatio()).isNull();
            assertThat(t.valuation()).isNotNull();
        });
    }

    @Test
    void 새_섹션만_살아있어도_빈_결과가_아니다() {
        stubAllFail();
        reset(binance);
        when(binance.getSpotDailyClosesSince(any(), any())).thenReturn(closes(10, "100"));
        when(binance.getFundingRate(any())).thenThrow(new RuntimeException("down"));
        when(binance.getOpenInterestHistory(any())).thenThrow(new RuntimeException("down"));
        when(binance.getLongShortAccountRatioHistory(any())).thenThrow(new RuntimeException("down"));

        assertThat(service.get(200).isEmpty()).isFalse();
    }

    @Test
    void 장기_일봉은_같은_UTC_날짜_안에서는_메인_TTL이_지나도_재호출하지_않고_자정이_지나면_다시_부른다() {
        service.get(200);
        now[0] = NOW.plus(CryptoMarketIndicatorService.TTL).plusSeconds(1);
        service.get(200);
        verify(binance, times(1)).getSpotDailyClosesSince(org.mockito.ArgumentMatchers.eq(StrategyTicker.BTC), any());
        verify(upbit, times(2)).getUsdtKrwPrice(); // 메인 캐시는 만료돼 재조립

        now[0] = TODAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(1);
        service.get(200);
        verify(binance, times(2)).getSpotDailyClosesSince(org.mockito.ArgumentMatchers.eq(StrategyTicker.BTC), any());
    }

    @Test
    void 장기_일봉_조회_실패는_캐시하지_않는다() {
        when(binance.getSpotDailyClosesSince(any(), any())).thenThrow(new RuntimeException("down"));
        service.get(200);
        now[0] = NOW.plus(CryptoMarketIndicatorService.TTL).plusSeconds(1);
        service.get(200);

        verify(binance, times(2)).getSpotDailyClosesSince(org.mockito.ArgumentMatchers.eq(StrategyTicker.BTC), any());
    }

    @Test
    void Binance_선물만_실패하면_김프는_유지하고_펀딩_OI만_null() {
        when(binance.getFundingRate(any())).thenThrow(new RuntimeException("fapi down"));
        when(binance.getOpenInterestHistory(any())).thenThrow(new RuntimeException("fapi down"));

        CryptoMarketIndicators r = service.get(200);

        assertThat(r.usdtPremium()).isNotNull();
        r.tickers().forEach(t -> {
            assertThat(t.kimchiPremium()).isNotNull();
            assertThat(t.fundingRate()).isNull();
            assertThat(t.openInterest()).isNull();
        });
    }

    @Test
    void 환율만_실패하면_official과_usdtPremium만_null() {
        when(fx.getUsdKrwRates(any(), any())).thenThrow(new RuntimeException("frankfurter down"));

        CryptoMarketIndicators r = service.get(200);

        assertThat(r.usdtPremium()).isNull();
        r.tickers().forEach(t -> {
            assertThat(t.kimchiPremium().current().official()).isNull();
            assertThat(t.kimchiPremium().current().tether()).isNotNull();
            assertThat(t.kimchiPremium().history()).isNotEmpty()
                    .allMatch(p -> p.official() == null && p.tether() != null);
            assertThat(t.fundingRate()).isNotNull();
        });
    }

    @Test
    void 모두_실패하면_전부_null이고_1분만_캐시한다() {
        stubAllFail();

        CryptoMarketIndicators r = service.get(200);
        service.get(200);

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.usdtPremium()).isNull();
        assertThat(r.halving()).isNull();
        r.tickers().forEach(t -> {
            assertThat(t.kimchiPremium()).isNull();
            assertThat(t.fundingRate()).isNull();
            assertThat(t.openInterest()).isNull();
            assertThat(t.valuation()).isNull();
            assertThat(t.longShortRatio()).isNull();
        });
        verify(upbit, times(1)).getUsdtKrwPrice();

        // 1분이 지나면 다시 조회한다
        now[0] = NOW.plus(CryptoMarketIndicatorService.EMPTY_TTL).plusSeconds(1);
        service.get(200);
        verify(upbit, times(2)).getUsdtKrwPrice();
    }

    @Test
    void 장애가_복구되면_빈_결과_캐시가_끝난_뒤_정상_결과를_10분_캐시한다() {
        stubAllFail();
        assertThat(service.get(200).isEmpty()).isTrue();

        reset(upbit, brokerStats, binance, blockHeight, coinbase); // throw 스텁 위에 when()을 다시 걸면 스텁 호출이 던지므로 초기화
        stubAllOk();
        now[0] = NOW.plus(CryptoMarketIndicatorService.EMPTY_TTL).plusSeconds(1);
        assertThat(service.get(200).isEmpty()).isFalse();
        service.get(200);

        verify(upbit, times(1)).getUsdtKrwPrice(); // reset 이후 복구 조회 1회 — 두 번째 get은 10분 캐시 hit
    }

    @Test
    void 십분_안의_재호출은_외부를_다시_부르지_않고_days만_자른다() {
        service.get(200);
        CryptoMarketIndicators r = service.get(30);

        verify(upbit, times(1)).getUsdtKrwPrice();
        assertThat(r.tickers().getFirst().kimchiPremium().history()).hasSizeLessThanOrEqualTo(30);
        assertThat(r.tickers().getFirst().valuation().history()).hasSize(30);
        assertThat(r.tickers().getFirst().valuation().current().date()).isEqualTo(TODAY.minusDays(1));
        assertThat(r.usdtPremium().history()).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    void TTL이_지나면_외부를_다시_부른다() {
        service.get(200);
        now[0] = NOW.plus(CryptoMarketIndicatorService.TTL).plusSeconds(1);
        service.get(200);

        verify(upbit, times(2)).getUsdtKrwPrice();
    }

    @Test
    void days_범위_밖이면_IllegalArgumentException() {
        assertThatThrownBy(() -> service.get(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.get(201)).isInstanceOf(IllegalArgumentException.class);
    }
}
