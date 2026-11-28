package com.kista.shared.kernel;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

// 통화 — broker(응답 통화 코드 파싱)와 계좌 응답(broker 파생 통화)이 공유하는 어휘. broker.domain.model에서 승격(복제 금지)
public enum Currency {
    KRW("원화", 0),  // 원화
    USD("미국 달러", 2),  // 미국 달러
    JPY("일본 엔", 0);  // 일본 엔

    @Getter
    private final String label; // 한국어 표시 이름 (메타 번들)
    private final int amountScale; // 금액 소수 자릿수 (반올림·표기 SSOT)

    Currency(String label, int amountScale) {
        this.label = label;
        this.amountScale = amountScale;
    }

    public int amountScale() {
        return amountScale;
    }

    // 통화 자릿수로 HALF_UP 반올림
    public BigDecimal round(BigDecimal amount) {
        return amount.setScale(amountScale, RoundingMode.HALF_UP);
    }

    // 통화 자릿수로 0 방향 절사(DOWN) — 상한 금액 표시용(표시액을 그대로 요청해도 통과)
    public BigDecimal truncate(BigDecimal amount) {
        return amount.setScale(amountScale, RoundingMode.DOWN);
    }

    // 표기: KRW는 "15,000원", 그 외는 "10.50 USD"
    public String format(BigDecimal amount) {
        String number = String.format(Locale.ROOT, "%,." + amountScale + "f", round(amount)); // 기본 로케일 무관 — 콤마 그룹·점 소수
        return this == KRW ? number + "원" : number + " " + name();
    }

    // 사용자 문장용 금액 표기 — USD는 kista-ui fmtUsd와 같은 "$1,234.50", 그 외는 format()("15,000원")
    public String display(BigDecimal amount) {
        return this == USD ? "$" + String.format(Locale.ROOT, "%,.2f", round(amount)) : format(amount);
    }

    // 브로커 응답 통화 코드 → Currency 변환. 미등록 통화이면 empty 반환 (필터링 용도)
    public static Optional<Currency> tryParse(String code) {
        if (code == null) return Optional.empty();
        try {
            return Optional.of(valueOf(code.trim()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
