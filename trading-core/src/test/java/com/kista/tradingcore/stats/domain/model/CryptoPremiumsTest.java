package com.kista.tradingcore.stats.domain.model;

import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CryptoPremiumsTest {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 16);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 17);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 18);
    private static final LocalDate MON = LocalDate.of(2026, 10, 19);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 김프는_국내가를_해외_원화환산가로_나눈_초과율이다() {
        assertThat(CryptoPremiums.premiumPercent(bd("147000000"), bd("140000000")))
                .isEqualByComparingTo("5.0000")
                .extracting(BigDecimal::scale).isEqualTo(4);
    }

    @Test
    void fillForward는_주말에_직전_영업일_값을_쓴다() {
        Map<LocalDate, BigDecimal> raw = Map.of(FRI, bd("1400"), MON, bd("1410"));

        assertThat(CryptoPremiums.fillForward(raw, SAT, MON))
                .containsOnly(Map.entry(SAT, bd("1400")), Map.entry(SUN, bd("1400")), Map.entry(MON, bd("1410")));
    }

    @Test
    void fillForward는_창_시작전_값이_없으면_그날을_제외한다() {
        Map<LocalDate, BigDecimal> raw = Map.of(MON, bd("1410"));

        assertThat(CryptoPremiums.fillForward(raw, SAT, MON)).containsOnly(Map.entry(MON, bd("1410")));
    }

    @Test
    void 김프_추이는_세_시계열_날짜_교집합만_쓴다() {
        List<CryptoCandle> coin = List.of(new CryptoCandle(SUN, bd("147000000")), new CryptoCandle(SAT, bd("100000000")));
        List<CryptoCandle> usdt = List.of(new CryptoCandle(SAT, bd("1400")), new CryptoCandle(SUN, bd("1400")));
        // 진행 중 봉(MON)은 Upbit에 없다
        List<DatedValue> binance = List.of(new DatedValue(SAT, bd("70000")), new DatedValue(SUN, bd("100000")),
                new DatedValue(MON, bd("101000")));

        List<DatedPremiumPair> result = CryptoPremiums.kimchiHistory(coin, usdt, binance,
                Map.of(SAT, bd("1400"), SUN, bd("1400")));

        assertThat(result).extracting(DatedPremiumPair::date).containsExactly(SAT, SUN);
        assertThat(result.get(1).tether()).isEqualByComparingTo("5.0000");
    }

    @Test
    void 환율이_없는_날은_official만_null이다() {
        List<DatedPremiumPair> result = CryptoPremiums.kimchiHistory(
                List.of(new CryptoCandle(SAT, bd("147000000"))), List.of(new CryptoCandle(SAT, bd("1400"))),
                List.of(new DatedValue(SAT, bd("100000"))), Map.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).official()).isNull();
        assertThat(result.get(0).tether()).isNotNull();
    }

    @Test
    void 테더김프_테더프리미엄_공식김프_곱셈관계가_성립한다() {
        BigDecimal fx = bd("1380");
        BigDecimal usdtKrw = bd("1410");
        DatedPremiumPair p = CryptoPremiums.kimchiHistory(
                List.of(new CryptoCandle(SAT, bd("147000000"))), List.of(new CryptoCandle(SAT, usdtKrw)),
                List.of(new DatedValue(SAT, bd("100000"))), Map.of(SAT, fx)).get(0);
        BigDecimal usdtPremium = CryptoPremiums.usdtPremiumHistory(
                List.of(new CryptoCandle(SAT, usdtKrw)), Map.of(SAT, fx)).get(0).value();

        double official = 1 + p.official().doubleValue() / 100;
        double product = (1 + p.tether().doubleValue() / 100) * (1 + usdtPremium.doubleValue() / 100);
        assertThat(official).isCloseTo(product, within(0.0001));
    }

    @Test
    void usdt_프리미엄은_환율이_있는_날만_계산한다() {
        List<DatedValue> result = CryptoPremiums.usdtPremiumHistory(
                List.of(new CryptoCandle(SAT, bd("1428")), new CryptoCandle(SUN, bd("1428"))), Map.of(SAT, bd("1400")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).date()).isEqualTo(SAT);
        assertThat(result.get(0).value()).isEqualByComparingTo("2.0000");
    }

    @Test
    void 펀딩비는_UTC_날짜별_평균이다() {
        List<FundingRateRecord> records = List.of(
                new FundingRateRecord(Instant.parse("2026-10-18T00:00:00Z"), bd("0.0001")),
                new FundingRateRecord(Instant.parse("2026-10-18T08:00:00Z"), bd("0.0002")),
                new FundingRateRecord(Instant.parse("2026-10-18T16:00:00Z"), bd("0.0003")),
                new FundingRateRecord(Instant.parse("2026-10-19T00:00:00Z"), bd("0.0005")));

        List<DatedValue> result = CryptoPremiums.fundingDailyAverage(records);

        assertThat(result).extracting(DatedValue::date).containsExactly(SUN, MON);
        assertThat(result.get(0).value()).isEqualByComparingTo("0.00020000").extracting(BigDecimal::scale).isEqualTo(8);
    }
}
