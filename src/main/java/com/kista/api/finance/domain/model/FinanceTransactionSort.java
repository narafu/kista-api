package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

// 거래 목록 정렬 기준 — wire 형식은 "<field>,<asc|desc>"
public record FinanceTransactionSort(Key key, boolean ascending) {

    public enum Key {
        TRANSACTION_DATE("transactionDate"),
        CATEGORY("category"),
        MEMO("memo"),
        AMOUNT("amount");

        private final String field; // 요청 파라미터에 쓰는 필드명

        Key(String field) {
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    public static final FinanceTransactionSort DEFAULT = new FinanceTransactionSort(Key.TRANSACTION_DATE, false); // 기본: 거래일 최신순

    public static FinanceTransactionSort parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        // 필드와 방향 분리 — 방향은 필수
        String[] parts = raw.split(",", -1);
        if (parts.length != 2) {
            throw invalid();
        }
        Key key = Arrays.stream(Key.values()).filter(k -> k.field.equals(parts[0])).findFirst().orElseThrow(FinanceTransactionSort::invalid);
        String direction = parts[1];
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw invalid();
        }
        return new FinanceTransactionSort(key, direction.equals("asc"));
    }

    public String wire() {
        return key.field + "," + (ascending ? "asc" : "desc");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("정렬 기준이 올바르지 않습니다.");
    }
}
