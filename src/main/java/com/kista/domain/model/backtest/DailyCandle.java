package com.kista.domain.model.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

// 백테스트용 일봉 OHLC — date는 미국 거래일 원본 (KST 변환은 소비처에서)
public record DailyCandle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}
