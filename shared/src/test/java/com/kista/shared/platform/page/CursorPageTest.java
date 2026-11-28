package com.kista.shared.platform.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CursorPageTest {

    @Test
    void size초과분이_있으면_절단하고_마지막_항목으로_커서를_만든다() {
        CursorPage<Integer> page = CursorPage.of(List.of(1, 2, 3), 2, n -> "c" + n);

        assertThat(page.items()).containsExactly(1, 2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("c2");
    }

    @Test
    void 정확히_size건이면_hasMore_false_커서_null() {
        CursorPage<Integer> page = CursorPage.of(List.of(1, 2), 2, n -> "c" + n);

        assertThat(page.items()).containsExactly(1, 2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void map은_커서와_hasMore를_보존한다() {
        CursorPage<String> mapped = CursorPage.of(List.of(1, 2, 3), 2, n -> "c" + n).map(n -> "v" + n);

        assertThat(mapped.items()).containsExactly("v1", "v2");
        assertThat(mapped.nextCursor()).isEqualTo("c2");
        assertThat(mapped.hasMore()).isTrue();
    }
}
