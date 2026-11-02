package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 진행 중 사이클 자금 추가 인바운드 파라미터 — 모두 nullable(예수금·주식 중 최소 하나 필수)
public record InjectCapitalCommand(
        BigDecimal depositAmount, // 추가할 예수금 (USD)
        Integer shares,           // 추가할 주식 수 (sharePrice와 함께 지정)
        BigDecimal sharePrice     // 추가할 주식의 매수단가 (shares와 함께 지정)
) {
}
