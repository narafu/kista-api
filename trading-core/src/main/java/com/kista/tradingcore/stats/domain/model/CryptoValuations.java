package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// 장기 일봉 밸류에이션 순수 계산 — 200주 이동평균·Mayer Multiple·ATH 대비 하락률
public final class CryptoValuations {

    static final int WMA200_DAYS = 1400; // 200주 = 1400일
    static final int MAYER_DAYS = 200; // Mayer Multiple 기준 200일 이동평균
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PRICE_SCALE = 2; // 이동평균 가격(USDT) 소수 자릿수
    private static final int RATIO_SCALE = 4; // 배수·하락률 % 소수 자릿수

    private CryptoValuations() {}

    // today 이전 완성 봉만으로 마지막 days일 밸류에이션, 오래된 순 — 각 일자는 그날까지의 봉만 쓴다
    public static List<DatedValuation> history(List<DatedValue> dailyCloses, LocalDate today, int days) {
        // 날짜 키 정렬·중복 제거, 진행 중인 오늘 봉(이후 포함) 제외
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
        dailyCloses.stream().filter(c -> c.date().isBefore(today)).forEach(c -> byDate.put(c.date(), c.value()));
        if (byDate.isEmpty()) throw new IllegalStateException("완성 일봉이 없습니다");

        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        List<BigDecimal> closes = new ArrayList<>(byDate.values());
        // prefix[i] = closes[0..i) 합 — 구간 평균을 O(1)로
        BigDecimal[] prefix = new BigDecimal[closes.size() + 1];
        prefix[0] = BigDecimal.ZERO;
        for (int i = 0; i < closes.size(); i++) prefix[i + 1] = prefix[i].add(closes.get(i));

        int first = Math.max(0, closes.size() - days);
        // 첫 출력일 이전까지의 최고 종가 — 이후는 일자별로 누적 갱신
        BigDecimal ath = closes.subList(0, first).stream().reduce(BigDecimal.ZERO, BigDecimal::max);
        List<DatedValuation> result = new ArrayList<>();
        for (int i = first; i < closes.size(); i++) {
            BigDecimal close = closes.get(i);
            ath = ath.max(close);
            BigDecimal wma200 = average(prefix, i, WMA200_DAYS);
            BigDecimal sma200 = average(prefix, i, MAYER_DAYS);
            result.add(new DatedValuation(dates.get(i), close,
                    wma200 == null ? null : wma200.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    wma200 == null ? null : ratio(close, wma200),
                    sma200 == null ? null : ratio(close, sma200),
                    close.divide(ath, MathContext.DECIMAL64).subtract(BigDecimal.ONE).multiply(HUNDRED)
                            .setScale(RATIO_SCALE, RoundingMode.HALF_UP)));
        }
        return result;
    }

    // closes[i-window+1..i] 단순평균 — 이력이 window보다 짧으면 null
    private static BigDecimal average(BigDecimal[] prefix, int i, int window) {
        if (i + 1 < window) return null;
        return prefix[i + 1].subtract(prefix[i + 1 - window]).divide(BigDecimal.valueOf(window), MathContext.DECIMAL64);
    }

    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return a.divide(b, MathContext.DECIMAL64).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
