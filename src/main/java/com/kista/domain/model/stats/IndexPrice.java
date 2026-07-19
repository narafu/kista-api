package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

// 벤치마크 지수 일별 종가 — tradeDate는 미국 거래일 원본 (KST 변환은 소비처에서)
public record IndexPrice(String symbol, LocalDate tradeDate, BigDecimal close) {}
