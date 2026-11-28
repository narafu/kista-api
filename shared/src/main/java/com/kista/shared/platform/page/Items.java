package com.kista.shared.platform.page;

import java.util.List;

// 전량형 목록 응답 봉투 — { "items": [...] }
public record Items<T>(
        List<T> items // 전량 목록
) {
    public static <T> Items<T> of(List<T> items) {
        return new Items<>(items);
    }
}
