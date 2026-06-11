package com.kista.domain.model.privacy;

import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;

// PRIVACY 전략 매수 배수 MAX 계산에 사용하는 기준가 정보
public record PrivacyCurrentBase(
        Ticker ticker,              // 대상 종목 (SOXL 고정)
        BigDecimal currentCycleStart, // 현재 사이클 시작 기준가
        LocalDate tradeDate         // 기준 매매표 적용 거래일
) {
    public PrivacyCurrentBase {
        if (currentCycleStart == null || currentCycleStart.signum() <= 0) {
            throw new IllegalStateException("[PRIVACY] currentCycleStart 이상: " + currentCycleStart);
        }
    }
}
