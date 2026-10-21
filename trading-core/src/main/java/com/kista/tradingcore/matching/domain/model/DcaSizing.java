package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaSizingType;

import java.math.BigDecimal;

// DCA 규칙 규모 — type별로 amount·percent 중 하나만 채운다
public record DcaSizing(DcaSizingType type, BigDecimal amount, BigDecimal percent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public DcaSizing {
        if (type == null) throw new IllegalArgumentException("규모 유형은 필수입니다.");
        if (type == DcaSizingType.AMOUNT) {
            if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount는 0보다 커야 합니다");
            if (percent != null) throw new IllegalArgumentException("고정 금액 규모에는 percent를 지정할 수 없습니다");
        } else {
            if (percent == null || percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("percent는 0 초과 100 이하여야 합니다");
            }
            if (amount != null) throw new IllegalArgumentException("보유 비율 규모에는 amount를 지정할 수 없습니다");
        }
    }
}
