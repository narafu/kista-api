package com.kista.platform.persistence;

import java.security.SecureRandom;
import java.util.UUID;

// RFC 9562 UUID v7 생성기 — JPA @UuidGenerator(VERSION_7)를 못 타는 경로(native INSERT·도메인 부여 id)의 PK용
// 앞 48bit가 unix ms라 시간순으로 인덱스 끝에 붙는다. 같은 ms 안의 순서는 보장하지 않는다
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom(); // id 추측 방지용 난수원 (thread-safe)

    private UuidV7() {
    }

    public static UUID next() {
        long msb = (System.currentTimeMillis() << 16) // unix_ts_ms 48bit
                | 0x7000L                               // version 7
                | (RANDOM.nextLong() & 0x0FFFL);        // rand_a 12bit
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10 + rand_b 62bit
        return new UUID(msb, lsb);
    }
}
