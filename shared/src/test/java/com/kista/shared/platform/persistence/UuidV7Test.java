package com.kista.shared.platform.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void next_returnsRfc9562Version7WithCurrentTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = UuidV7.next();
        long after = System.currentTimeMillis();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 9562 variant(10xx)
        // 앞 48bit = unix epoch ms
        assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after);
    }

    @Test
    void next_sortsByCreationTimeAcrossMilliseconds() throws InterruptedException {
        UUID first = UuidV7.next();
        Thread.sleep(2);
        UUID second = UuidV7.next();

        // PostgreSQL uuid 비교와 같은 부호 없는 바이트 순서 — 문자열 비교로 확인
        assertThat(second.toString()).isGreaterThan(first.toString());
        assertThat(second).isNotEqualTo(first);
    }
}
