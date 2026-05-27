package com.kista.domain.model.kis;

import java.util.Optional;

public enum Currency {
    KRW, // 원화
    USD, // 미국 달러
    JPY; // 일본 엔

    // KIS 응답 crcy_cd → Currency 변환. 미등록 통화이면 empty 반환 (필터링 용도)
    public static Optional<Currency> tryParse(String code) {
        if (code == null) return Optional.empty();
        try {
            return Optional.of(valueOf(code.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
