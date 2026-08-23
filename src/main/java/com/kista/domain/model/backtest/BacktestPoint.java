package com.kista.domain.model.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

// 백테스트 일별 자산 곡선 한 점 — 필드명은 EquityCurveResponse.Point와 동일하게 유지(프론트 차트 컴포넌트 재사용)
public record BacktestPoint(
        LocalDate date,        // 거래일
        BigDecimal totalAsset, // 총자산 = 예수금 + 보유분 종가 평가액
        BigDecimal principal   // 원금 = 시드 + 실제 반영된 적립/인출 누계
) {}
