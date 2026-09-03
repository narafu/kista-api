package com.kista.broker.domain.model;

import java.math.BigDecimal;

// 현재가(current)와 전일종가(prevClose) — trading.PriceSnapshot과 필드 동일한 broker 소유 복제(모듈 경계상 공유 불가)
public record PriceSnapshot(BigDecimal current, BigDecimal prevClose) {}
