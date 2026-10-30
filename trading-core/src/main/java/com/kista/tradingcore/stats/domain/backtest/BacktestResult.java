package com.kista.tradingcore.stats.domain.backtest;

import java.time.LocalDate;
import java.util.List;

// 백테스트 실행 결과 — 자산 곡선 + 성과 요약 + 해석 주의사항 경고
public record BacktestResult(
        List<BacktestPoint> points, // 일별 자산 곡선
        BacktestSummary summary,    // 성과 요약
        List<LocalDate> cycleStartDates, // 두 번째 사이클부터 각 사이클 시작일 (points 날짜 축)
        List<String> warnings       // 엔진 경고 + 체결 모델/현금흐름 안내
) {}
