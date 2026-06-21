package com.kista.domain.model.auth;

import java.time.Duration;

public final class TokenConstants {
    public static final Duration AT_TTL = Duration.ofDays(1);

    private TokenConstants() {}
}
