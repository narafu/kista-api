package com.kista.shared.platform.page;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

// 커서 페이지 응답 — nextCursor는 마지막 페이지면 키 자체를 생략한다
public record CursorPage<T>(
        List<T> items, // 현재 페이지 항목
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor, // null이면 마지막 페이지
        boolean hasMore // 다음 페이지 존재 여부
) {
    // fetched는 size+1건까지 읽은 결과 — 초과분으로 hasMore를 판정하고 앞 size건만 싣는다
    public static <T> CursorPage<T> of(List<T> fetched, int size, Function<T, String> cursorOf) {
        boolean hasMore = fetched.size() > size;
        List<T> items = hasMore ? List.copyOf(fetched.subList(0, size)) : List.copyOf(fetched);
        return new CursorPage<>(items, hasMore ? cursorOf.apply(items.getLast()) : null, hasMore);
    }

    public <R> CursorPage<R> map(Function<? super T, ? extends R> f) {
        return new CursorPage<>(items.stream().<R>map(f).toList(), nextCursor, hasMore);
    }
}
