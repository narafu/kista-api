package com.kista.domain.model;

import java.time.Instant;

public class CooldownException extends RuntimeException {

    private final Instant retryAfter; // 재신청 가능 시각

    public CooldownException(Instant retryAfter) {
        super("재신청 대기 중입니다. 가능 시각: " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }
}
