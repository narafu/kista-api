package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

// 거래 목록 keyset 커서 — 마지막 행의 정렬 값(문자열 표현, 값 없음은 null)과 id
public record FinanceTransactionCursor(
        FinanceTransactionSort sort, // 커서를 만든 정렬 기준
        @Nullable String value,      // 마지막 행의 정렬 값
        UUID id                      // 마지막 행 id(동순위 구분)
) {

    public String encode() {
        String body = sort.wire() + "|" + id + "|" + (value == null ? "N" : "V" + value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    // 디코딩 실패·형식 오류·sort 불일치는 모두 같은 IAE
    public static FinanceTransactionCursor decode(String raw, FinanceTransactionSort expected) {
        try {
            String body = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            // value에 '|'가 있어도 되도록 3분할
            String[] parts = body.split("\\|", 3);
            if (parts.length != 3 || !parts[0].equals(expected.wire())) {
                throw invalid();
            }
            String tail = parts[2];
            String value = switch (tail.charAt(0)) {
                case 'N' -> null;
                case 'V' -> tail.substring(1);
                default -> throw invalid();
            };
            if (tail.length() > 1 && tail.charAt(0) == 'N') {
                throw invalid();
            }
            validateValue(expected.key(), value);
            return new FinanceTransactionCursor(expected, value, UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    // 날짜·금액 값은 SQL 캐스트 전에 형식을 확인한다(NOT NULL 컬럼이라 null도 불가)
    private static void validateValue(FinanceTransactionSort.Key key, @Nullable String value) {
        switch (key) {
            case TRANSACTION_DATE -> LocalDate.parse(java.util.Objects.requireNonNull(value));
            case AMOUNT -> Long.parseLong(java.util.Objects.requireNonNull(value));
            case MEMO, CATEGORY -> { }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("커서가 올바르지 않습니다.");
    }
}
