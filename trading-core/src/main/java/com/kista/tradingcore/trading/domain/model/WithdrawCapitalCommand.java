package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 진행 중 사이클 투자금 인출 인바운드 파라미터
public record WithdrawCapitalCommand(
        BigDecimal amount // 인출할 예수금 (USD, 0보다 커야 함)
) {
}
