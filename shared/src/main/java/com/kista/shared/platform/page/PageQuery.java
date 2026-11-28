package com.kista.shared.platform.page;

import org.jspecify.annotations.Nullable;

// 커서 페이지 요청 — cursor는 불투명 문자열, size는 1..MAX_SIZE로 정규화된 값
public record PageQuery(
        @Nullable String cursor, // 불투명 커서, null이면 첫 페이지
        int size                 // 정규화된 페이지 크기
) {
    public static final int DEFAULT_SIZE = 50; // size 생략 시 기본값
    public static final int MAX_SIZE = 200;    // 한 페이지 상한

    public static PageQuery of(@Nullable String cursor, @Nullable Integer size) {
        // 빈 문자열 cursor는 첫 페이지로 취급
        String normalized = cursor == null || cursor.isEmpty() ? null : cursor;
        return new PageQuery(normalized, size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE));
    }

    // hasMore 판정용으로 한 건 더 읽는 조회 크기
    public int fetchSize() {
        return size + 1;
    }
}
