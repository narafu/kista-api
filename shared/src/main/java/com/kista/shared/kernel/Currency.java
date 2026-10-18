package com.kista.shared.kernel;

import java.util.Optional;

// 통화 — broker(KIS crcy_cd 파싱)와 계좌 응답(broker 파생 통화)이 공유하는 어휘. broker.domain.model에서 승격(복제 금지)
public enum Currency {
    KRW, // 원화
    USD, // 미국 달러
    JPY; // 일본 엔

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
