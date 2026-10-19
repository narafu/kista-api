package com.kista.tradingcore.stats.domain.model;

import com.kista.tradingcore.broker.domain.model.CryptoCandle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 김프·테더 프리미엄·코인베이스 프리미엄·펀딩비 일평균 순수 계산 — 날짜 키로만 조인한다(인덱스 정렬 금지)
public final class CryptoPremiums {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PREMIUM_SCALE = 4; // 프리미엄 % 소수 자릿수
    private static final int FUNDING_SCALE = 8; // 펀딩비 평균 소수 자릿수

    private CryptoPremiums() {}

    // (국내가 ÷ 해외 원화환산가 − 1) × 100
    public static BigDecimal premiumPercent(BigDecimal domestic, BigDecimal foreignKrw) {
        return domestic.divide(foreignKrw, MathContext.DECIMAL64)
                .subtract(BigDecimal.ONE).multiply(HUNDRED)
                .setScale(PREMIUM_SCALE, RoundingMode.HALF_UP);
    }

    // [from, to] 각 날짜에 그 날짜 이하 최신 raw 값을 채운다 — 이전 값이 없는 날짜는 제외
    public static Map<LocalDate, BigDecimal> fillForward(Map<LocalDate, BigDecimal> raw, LocalDate from, LocalDate to) {
        TreeMap<LocalDate, BigDecimal> sorted = new TreeMap<>(raw);
        Map<LocalDate, BigDecimal> filled = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            var latest = sorted.floorEntry(d);
            if (latest != null) filled.put(d, latest.getValue());
        }
        return filled;
    }

    // 세 시계열 날짜 교집합만, 오래된 순
    public static List<DatedPremiumPair> kimchiHistory(List<CryptoCandle> upbitCoin, List<CryptoCandle> upbitUsdt,
                                                       List<DatedValue> binanceCloses, Map<LocalDate, BigDecimal> fxByDate) {
        Map<LocalDate, BigDecimal> usdt = closeByDate(upbitUsdt);
        Map<LocalDate, BigDecimal> binance = byDate(binanceCloses);
        return closeByDate(upbitCoin).entrySet().stream()
                .filter(e -> usdt.containsKey(e.getKey()) && binance.containsKey(e.getKey()))
                .map(e -> {
                    LocalDate date = e.getKey();
                    BigDecimal fx = fxByDate.get(date);
                    return new DatedPremiumPair(date,
                            fx == null ? null : premiumPercent(e.getValue(), binance.get(date).multiply(fx)),
                            premiumPercent(e.getValue(), binance.get(date).multiply(usdt.get(date))));
                })
                .toList();
    }

    // 환율이 있는 날만 USDT 원화가 vs 공식 환율 프리미엄
    public static List<DatedValue> usdtPremiumHistory(List<CryptoCandle> upbitUsdt, Map<LocalDate, BigDecimal> fxByDate) {
        return closeByDate(upbitUsdt).entrySet().stream()
                .filter(e -> fxByDate.containsKey(e.getKey()))
                .map(e -> new DatedValue(e.getKey(), premiumPercent(e.getValue(), fxByDate.get(e.getKey()))))
                .toList();
    }

    // 테더 보정 코인베이스 프리미엄 추이 — 세 시계열 UTC 날짜 교집합 중 today 이전(완성 봉)만, 오래된 순
    // (코인베이스 <코인>-USD ÷ (바이낸스 <코인>USDT × 코인베이스 USDT-USD) − 1) × 100
    public static List<DatedValue> coinbasePremiumHistory(List<DatedValue> coinbaseCoin, List<DatedValue> coinbaseUsdt,
                                                          List<DatedValue> binanceCloses, LocalDate today) {
        Map<LocalDate, BigDecimal> usdt = byDate(coinbaseUsdt);
        Map<LocalDate, BigDecimal> binance = byDate(binanceCloses);
        return byDate(coinbaseCoin).entrySet().stream()
                .filter(e -> e.getKey().isBefore(today) && usdt.containsKey(e.getKey()) && binance.containsKey(e.getKey()))
                .map(e -> new DatedValue(e.getKey(),
                        premiumPercent(e.getValue(), binance.get(e.getKey()).multiply(usdt.get(e.getKey())))))
                .toList();
    }

    // fundingTime의 UTC 날짜별 평균, 오래된 순
    public static List<DatedValue> fundingDailyAverage(List<FundingRateRecord> records) {
        Map<LocalDate, List<BigDecimal>> byDay = new TreeMap<>(records.stream().collect(Collectors.groupingBy(
                r -> r.fundingTime().atZone(ZoneOffset.UTC).toLocalDate(),
                Collectors.mapping(FundingRateRecord::rate, Collectors.toList()))));
        return byDay.entrySet().stream()
                .map(e -> new DatedValue(e.getKey(), e.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(e.getValue().size()), FUNDING_SCALE, RoundingMode.HALF_UP)))
                .toList();
    }

    // 날짜 키 값 맵 (날짜 오름차순)
    private static Map<LocalDate, BigDecimal> byDate(List<DatedValue> values) {
        return values.stream().collect(Collectors.toMap(DatedValue::date, DatedValue::value, (a, b) -> b, TreeMap::new));
    }

    // 날짜 키 종가 맵 (날짜 오름차순)
    private static Map<LocalDate, BigDecimal> closeByDate(List<CryptoCandle> candles) {
        return candles.stream().collect(Collectors.toMap(CryptoCandle::kstDate, CryptoCandle::close, (a, b) -> b, TreeMap::new));
    }
}
