package com.kista.domain.model.auth;

// refresh 인증 실패 전용 — GlobalExceptionHandler가 401로 매핑
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
