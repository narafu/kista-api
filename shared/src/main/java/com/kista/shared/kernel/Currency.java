package com.kista.shared.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

// 통화 — broker(KIS crcy_cd 파싱)와 계좌 응답(broker 파생 통화)이 공유하는 어휘. broker.domain.model에서 승격(복제 금지)
public enum Currency {
    KRW(0),  // 원화
    USD(2),  // 미국 달러
    JPY(0),  // 일본 엔
    USDT(2); // 테더

    private final int amountScale; // 금액 소수 자릿수 (반올림·표기 SSOT)

    Currency(int amountScale) {
        this.amountScale = amountScale;
    }

    public int amountScale() {
        return amountScale;
    }

    // 통화 자릿수로 HALF_UP 반올림
    public BigDecimal round(BigDecimal amount) {
        return amount.setScale(amountScale, RoundingMode.HALF_UP);
    }

    // 표기: KRW는 "15,000원", 그 외는 "10.50 USDT"
    public String format(BigDecimal amount) {
        String number = ("%,." + amountScale + "f").formatted(round(amount));
        return this == KRW ? number + "원" : number + " " + name();
    }

    // KIS 응답 crcy_cd → Currency 변환. 미등록 통화이면 empty 반환 (필터링 용도)
    public static Optional<Currency> tryParse(String code) {
        if (code == null) return Optional.empty();
        try {
            return Optional.of(valueOf(code.trim()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
