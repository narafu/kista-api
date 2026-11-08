package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Currency;

import java.math.BigDecimal;

// 예수금·투자금 입력 검증의 단일 구현 — 등록(전 전략 유형)·시드 수정·투자금 추가/인출이 함께 쓴다
public final class DepositAmounts {

    private DepositAmounts() {}

    // 등록 예수금 — 생략(null)은 0으로 정규화, 음수 거부(0은 허용), 계좌 통화 자릿수 검증
    public static BigDecimal normalizeForRegistration(BigDecimal deposit, Currency currency) {
        if (deposit == null) return BigDecimal.ZERO;
        if (deposit.signum() < 0) throw new IllegalArgumentException("예수금은 0 이상이어야 합니다.");
        requireScale(deposit, currency);
        return deposit;
    }

    // 금액 자릿수 — 계좌 통화 소수 자릿수 이하(KRW 정수·USD/USDT 소수 2자리)
    public static void requireScale(BigDecimal amount, Currency currency) {
        if (amount.stripTrailingZeros().scale() > currency.amountScale()) {
            throw new IllegalArgumentException(currency.amountScale() == 0 ? "금액은 정수로 입력해야 합니다."
                    : "금액은 소수 %d자리까지 입력할 수 있습니다.".formatted(currency.amountScale()));
        }
    }
}
