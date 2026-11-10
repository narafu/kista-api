package com.kista.tradingcore.privacy.domain.model;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

// PRIVACY 전략 매수 배수 MAX 계산에 사용하는 기준가 정보
public record PrivacyCurrentBase(
        StrategyTicker ticker,              // 대상 종목 (SOXL 고정)
        BigDecimal currentCycleStart, // 현재 사이클 시작 기준가
        LocalDate tradeDate,        // 기준 매매표 적용 거래일
        BigDecimal currentCycleRealizedPnl, // 현재 사이클 누적 실현손익 — 중간부터 시작 배수 산출용
        BigDecimal avgPrice         // FIDA 보유 평단 (보유 0이면 null) — 중간부터 시작 배수 산출용
) {
    public PrivacyCurrentBase {
        if (currentCycleStart == null || currentCycleStart.signum() <= 0) {
            throw new IllegalStateException("PRIVACY 기준표의 사이클 시작 금액은 0보다 커야 합니다.");
        }
    }

    // PRIVACY 중간부터 시작 시작금액 — 원가 기준 자산 비율로 배수를 구해 FIDA 사이클 시작금액에 곱한다(운영 등록·백테스트 공용)
    // 배수 = (예수금 + 보유×FIDA 평단) ÷ (FIDA 사이클 시작금액 + 실현손익), 소수 2자리 버림(PrivacyStrategy 배수 산출과 동일)
    // 예: 3,445.25 + 417×125.95 = 55,966.40 ÷ (14,434.58 − 454.07) = 4.00배 → 57,738.32
    public BigDecimal bootstrapStartAmount(BigDecimal deposit, BigDecimal holdings) {
        BigDecimal fidaBookValue = currentCycleStart.add(currentCycleRealizedPnl);
        if (avgPrice == null || fidaBookValue.signum() <= 0) {
            throw new IllegalArgumentException("PRIVACY 기준 매매표에 보유 평단이 없어 중간부터 시작할 수 없습니다.");
        }
        BigDecimal userBookValue = deposit.add(holdings.multiply(avgPrice));
        BigDecimal multiple = userBookValue.divide(fidaBookValue, 2, RoundingMode.FLOOR);
        if (multiple.signum() <= 0) {
            throw new IllegalArgumentException("보유 자산이 PRIVACY 기준 매매표 대비 너무 적어 중간부터 시작할 수 없습니다.");
        }
        // CEILING — 매매 시 startAmount ÷ currentCycleStart를 FLOOR로 되돌리므로 반올림 내림이면 배수가 0.01 낮아진다
        return multiple.multiply(currentCycleStart).setScale(Currency.USD.amountScale(), RoundingMode.CEILING);
    }
}
