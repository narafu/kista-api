package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

// 일별 전략 운용 자산 스냅샷 합산 (KST 거래일 기준)
public record EquityPoint(LocalDate date, BigDecimal totalAsset, BigDecimal principal) {}
