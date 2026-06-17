package com.kista.domain.model.strategy;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TradingReport(
        LocalDate date,                  // 거래일
        BigDecimal totalBoughtUsd,       // 당일 총 매수 체결액 (USD)
        BigDecimal totalSoldUsd          // 당일 총 매도 체결액 (USD)
) {}
