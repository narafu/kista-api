package com.kista.broker.domain.model;

import java.math.BigDecimal;

// 잔고 조회 결과 — LiveBalancePort 반환 타입. trading은 이 값으로 자신의 AccountBalance를 구성한다
public record BrokerBalance(
        int holdings,          // 보유 수량
        BigDecimal avgPrice,   // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit  // 통합주문가능금액 (USD)
) {}
