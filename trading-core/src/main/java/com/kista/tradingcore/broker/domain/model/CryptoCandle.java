package com.kista.tradingcore.broker.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 완성된 코인 일봉 — kstDate는 봉 시작일(KST 09:00 시작, 다음날 09:00 마감), close는 마감 종가
public record CryptoCandle(LocalDate kstDate, BigDecimal close) {
}
