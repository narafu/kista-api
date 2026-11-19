package com.kista.shared.platform.persistence;

import com.kista.shared.platform.persistence.KistaFlywayProperties.Migration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KistaFlywayPropertiesTest {

    private static final Migration VALID = new Migration("classpath:db/migration", "history", "public");

    @Test
    void nullList_throws() {
        assertThatThrownBy(() -> new KistaFlywayProperties(null))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("니다.");
    }

    @Test
    void emptyList_throws() {
        assertThatThrownBy(() -> new KistaFlywayProperties(List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("니다.");
    }

    @Test
    void blankField_throws() {
        assertThatThrownBy(() -> new Migration(" ", "history", "public")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Migration("loc", "", "public")).isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("니다.");
        assertThatThrownBy(() -> new Migration("loc", "history", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validList_ok() {
        assertThatCode(() -> new KistaFlywayProperties(List.of(VALID))).doesNotThrowAnyException();
    }
}
