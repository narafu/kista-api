package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;
import com.kista.tradingcore.stats.application.port.output.BinanceMarketDataPort;
import com.kista.tradingcore.stats.application.port.output.HistoricalFxRatePort;
import com.kista.tradingcore.stats.application.usecase.BrokerStatisticsUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CryptoMarketIndicatorServiceTest {

    static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    CryptoExchangePort upbit = mock(CryptoExchangePort.class);
    BinanceMarketDataPort binance = mock(BinanceMarketDataPort.class);
    HistoricalFxRatePort fx = mock(HistoricalFxRatePort.class);
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
        service = new CryptoMarketIndicatorService(exchanges, binance, fx, brokerStats,
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
        when(binance.getSpotDailyCloses(any(), anyInt())).thenReturn(closes(200, "100000"));
        when(binance.getFundingRate(any())).thenReturn(new FundingRateQuote(new BigDecimal("0.0001"), NOW.plusSeconds(3600)));
        when(binance.getFundingHistory(any(), any())).thenReturn(List.of(new FundingRateRecord(NOW.minusSeconds(86400), new BigDecimal("0.0001"))));
        when(binance.getOpenInterestHistory(any())).thenReturn(List.of(new DatedValue(TODAY.minusDays(1), new BigDecimal("5"))));
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
        });
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
    void 모두_실패하면_전부_null이고_캐시하지_않는다() {
        when(upbit.getUsdtKrwPrice()).thenThrow(new RuntimeException("upbit down"));
        when(brokerStats.currentExchangeRate()).thenThrow(new RuntimeException("fx down"));
        when(binance.getFundingRate(any())).thenThrow(new RuntimeException("down"));
        when(binance.getOpenInterestHistory(any())).thenThrow(new RuntimeException("down"));

        CryptoMarketIndicators r = service.get(200);
        service.get(200);

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.usdtPremium()).isNull();
        r.tickers().forEach(t -> {
            assertThat(t.kimchiPremium()).isNull();
            assertThat(t.fundingRate()).isNull();
            assertThat(t.openInterest()).isNull();
        });
        verify(upbit, times(2)).getUsdtKrwPrice();
    }

    @Test
    void 십분_안의_재호출은_외부를_다시_부르지_않고_days만_자른다() {
        service.get(200);
        CryptoMarketIndicators r = service.get(30);

        verify(upbit, times(1)).getUsdtKrwPrice();
        assertThat(r.tickers().getFirst().kimchiPremium().history()).hasSizeLessThanOrEqualTo(30);
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
