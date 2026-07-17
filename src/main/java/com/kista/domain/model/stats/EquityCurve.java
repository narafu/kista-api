package com.kista.domain.model.stats;

import java.util.List;

// benchmark의 tradeDate는 KST 변환(+1일) 완료 상태
public record EquityCurve(List<EquityPoint> points, List<IndexPrice> benchmark) {}
