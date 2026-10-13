package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

// DCA 조건 지표 — strategy_dca_condition.indicator 상수명과 직결(변경 금지). 평가 구현은 단계 ②·③
@Getter
@RequiredArgsConstructor
public enum DcaIndicator {
    AVG_PRICE("평단가", "전일 종가 vs 평단가 × (1 + n%)"),
    SMA("이동평균선", "전일 종가 vs SMA(기간)"),
    RSI("RSI(14)", "RSI(14) vs 기준값(0~100)"),
    FEAR_GREED("공포탐욕지수(CNN)", "CNN 공포탐욕지수 vs 기준값(0~100)");

    // SMA 기간 옵션 SSOT — 메타 응답 smaPeriods와 등록 검증이 공유
    public static final List<Integer> SMA_PERIODS = List.of(20, 50, 120, 200);

    private final String label;       // 한국어 표시 이름
    private final String description; // 비교 방식 설명 (UI 메타)
}
