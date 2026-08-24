package com.kista.domain.model.backtest;

import java.util.List;

// 백테스트 실행 결과 — 자산 곡선 + 성과 요약 + 해석 주의사항 경고
public record BacktestResult(
        List<BacktestPoint> points, // 일별 자산 곡선
        BacktestSummary summary,    // 성과 요약
        List<String> warnings       // 엔진 경고 + 체결 모델/현금흐름 안내
) {}
