package com.kista.stats.domain.model.backtest;

import java.math.BigDecimal;

// 백테스트 성과 요약 — 수익률 지표는 자산 곡선의 시작·끝 두 지점만으로 산출(중간 델타 미사용, 외부 현금흐름 미반영)
public record BacktestSummary(
        BigDecimal finalAsset,      // 마지막 포인트 총자산
        BigDecimal totalInvested,   // 마지막 포인트 원금(시드 + 적립/인출 누계) — 정보성 필드, 수익률 계산 미관여
        BigDecimal totalReturnRate, // 누적수익률 (0.15 = +15%)
        BigDecimal cagr,            // 연환산수익률 — 구간이 하루도 안 되면 null
        BigDecimal mdd,             // 최대낙폭 (-0.30 = -30%)
        int tradeCount,             // 체결 건수 누계
        int cycleCount              // 진행된 사이클 수
) {}
