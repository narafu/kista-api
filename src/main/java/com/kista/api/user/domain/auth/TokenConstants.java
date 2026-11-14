package com.kista.api.user.domain.auth;

import java.time.Duration;

public final class TokenConstants {
    public static final Duration AT_TTL = Duration.ofDays(1);
    public static final Duration RT_TTL = Duration.ofDays(5); // 리프레시 토큰 수명 — DB 만료와 쿠키 maxAge가 함께 쓴다

    private TokenConstants() {}
}
