package com.kista.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SHA-256 hex 유틸리티")
class Sha256Test {

    // echo -n "test" | shasum -a 256 로 계산한 알려진 다이제스트
    private static final String KNOWN_TEST_DIGEST =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    @DisplayName("고정 입력에 대해 알려진 SHA-256 hex 다이제스트를 반환한다")
    void hex_knownInput_matchesExpectedDigest() {
        String actual = Sha256.hex("test");

        assertThat(actual).hasSize(64).isEqualTo(KNOWN_TEST_DIGEST);
    }

    @Test
    @DisplayName("동일 입력에 대해 결정적으로 동일한 결과를 반환한다")
    void hex_deterministicForSameInput() {
        assertThat(Sha256.hex("test")).isEqualTo(Sha256.hex("test"));
    }
}
