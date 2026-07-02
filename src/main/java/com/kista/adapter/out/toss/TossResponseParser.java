package com.kista.adapter.out.toss;

import java.math.BigDecimal;

// Toss API 응답 문자열 파싱 공용 헬퍼 — 패키지 내부 전용
final class TossResponseParser {

    private TossResponseParser() {}

    // Toss 응답 숫자 문자열 → BigDecimal, null·공백 시 ZERO
    static BigDecimal parseBdOrZero(String s) {
        return s != null && !s.isBlank() ? new BigDecimal(s) : BigDecimal.ZERO;
    }

    // Toss 응답 정수 문자열 → int, null·공백 시 0
    static int parseIntOrZero(String s) {
        return s != null && !s.isBlank() ? Integer.parseInt(s) : 0;
    }
}
