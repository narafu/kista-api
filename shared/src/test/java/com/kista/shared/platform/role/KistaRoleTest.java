package com.kista.shared.platform.role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KistaRoleTest {

    @Test
    void 소문자_role_값을_파싱한다() {
        assertThat(KistaRole.parse("web")).isEqualTo(KistaRole.WEB);
        assertThat(KistaRole.parse("worker")).isEqualTo(KistaRole.WORKER);
        assertThat(KistaRole.parse(" all ")).isEqualTo(KistaRole.ALL);
    }

    @Test
    void 누락_빈값_오타_대문자는_예외() {
        for (String bad : new String[]{null, " ", "wrker", "WORKER"}) {
            assertThatThrownBy(() -> KistaRole.parse(bad)).isInstanceOf(IllegalStateException.class);
        }
    }
}
