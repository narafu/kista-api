package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// 현재가(current)와 전일종가(prevClose)를 함께 보유하는 가격 스냅샷 — KIS·Toss 공용
public record PriceSnapshot(BigDecimal current, BigDecimal prevClose) {}
