package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Hash Ribbons 순수 계산 — 해시레이트 30·60일 이동평균 교차로 채굴자 항복/회복 판정
public final class CryptoHashRibbons {

    private static final int FAST_DAYS = 30; // 단기 이동평균 일수
    private static final int SLOW_DAYS = 60; // 장기 이동평균 일수
    private static final int SCALE = 2; // 이동평균 소수 자릿수

    private CryptoHashRibbons() {}

    // today 이전 완성 일자만으로 마지막 days일 Hash Ribbons, 오래된 순 — SMA는 전체 이력으로 워밍업
    public static List<DatedHashRibbon> history(List<DatedValue> dailyHashratesEh, LocalDate today, int days) {
        // 날짜 키 정렬·중복 제거, 진행 중인 오늘(이후 포함) 제외
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
        dailyHashratesEh.stream().filter(c -> c.date().isBefore(today)).forEach(c -> byDate.put(c.date(), c.value()));
        if (byDate.isEmpty()) throw new IllegalStateException("완성 해시레이트 점이 없습니다");

        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        List<BigDecimal> values = new ArrayList<>(byDate.values());
        // prefix[i] = values[0..i) 합 — 구간 평균을 O(1)로
        BigDecimal[] prefix = new BigDecimal[values.size() + 1];
        prefix[0] = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) prefix[i + 1] = prefix[i].add(values.get(i));

        // ponytail: 점 개수 기준 SMA, mempool 결측일이 생기면 날짜 창으로 전환
        List<DatedHashRibbon> all = new ArrayList<>();
        DatedHashRibbon.State prev = null;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal raw30 = average(prefix, i, FAST_DAYS);
            BigDecimal raw60 = average(prefix, i, SLOW_DAYS);
            // 상태는 반올림 전 평균으로 판정 — 반환값만 scale 2
            DatedHashRibbon.State state = raw60 == null ? null
                    : raw30.compareTo(raw60) < 0 ? DatedHashRibbon.State.CAPITULATION : DatedHashRibbon.State.RECOVERY;
            boolean buy = prev == DatedHashRibbon.State.CAPITULATION && state == DatedHashRibbon.State.RECOVERY;
            all.add(new DatedHashRibbon(dates.get(i), values.get(i), round(raw30), round(raw60), state, buy));
            prev = state;
        }
        return all.subList(Math.max(0, all.size() - days), all.size());
    }

    private static BigDecimal round(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // values[i-window+1..i] 단순평균(반올림 전) — 이력이 window보다 짧으면 null
    private static BigDecimal average(BigDecimal[] prefix, int i, int window) {
        if (i + 1 < window) return null;
        return prefix[i + 1].subtract(prefix[i + 1 - window])
                .divide(BigDecimal.valueOf(window), MathContext.DECIMAL64);
    }
}
