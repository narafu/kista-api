package com.kista.tradingcore.broker.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

// 공용 시세 일봉 조회 조건 — 기간(from~to) 또는 "to까지 최근 count개" 둘 중 하나다. 날짜는 전부 미국 세션일
public record DailyCandleQuery(
        @Nullable LocalDate from,   // 기간 조회 시작일(포함). count 조회면 null
        @Nullable LocalDate to,     // 종료일(포함). null이면 가장 최근 봉까지(진행 중인 당일 봉 포함)
        @Nullable Integer count,    // 최근 N개 조회. 기간 조회면 null
        Adjustment adjustment       // 수정주가 방식
) {

    // 수정주가 방식 — 원장·체결과 맞출 값은 RAW, 분할을 넘는 이력은 SPLIT, 지표 계산은 ALL
    public enum Adjustment { RAW, SPLIT, ALL }

    public DailyCandleQuery {
        if ((from == null) == (count == null)) {
            throw new IllegalArgumentException("일봉 조회는 기간과 개수 중 하나만 지정해야 합니다.");
        }
    }

    // from~to(양끝 포함) 기간
    public static DailyCandleQuery range(LocalDate from, LocalDate to, Adjustment adjustment) {
        return new DailyCandleQuery(from, to, null, adjustment);
    }

    // 가장 최근 count개(진행 중인 당일 봉 포함) — 차트
    public static DailyCandleQuery latest(int count, Adjustment adjustment) {
        return new DailyCandleQuery(null, null, count, adjustment);
    }

    // usSessionDate(미포함) 이전 확정 봉 count개 — DCA 지표·전일 종가 이력
    public static DailyCandleQuery lastBefore(LocalDate usSessionDate, int count, Adjustment adjustment) {
        return new DailyCandleQuery(null, usSessionDate.minusDays(1), count, adjustment);
    }
}
