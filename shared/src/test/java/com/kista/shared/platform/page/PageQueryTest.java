package com.kista.shared.platform.page;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void size_null은_기본_50() {
        assertThat(PageQuery.of(null, null).size()).isEqualTo(50);
    }

    @Test
    void size는_1에서_200으로_정규화된다() {
        assertThat(PageQuery.of(null, 0).size()).isEqualTo(1);
        assertThat(PageQuery.of(null, 500).size()).isEqualTo(200);
        assertThat(PageQuery.of(null, 30).size()).isEqualTo(30);
    }

    @Test
    void 빈_cursor는_null로_정규화된다() {
        assertThat(PageQuery.of("", 10).cursor()).isNull();
        assertThat(PageQuery.of("abc", 10).cursor()).isEqualTo("abc");
    }

    @Test
    void fetchSize는_size_더하기_1() {
        assertThat(PageQuery.of(null, 10).fetchSize()).isEqualTo(11);
    }
}
