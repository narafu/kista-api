package com.kista.domain.model.toss;

// Toss 외부 API 호출 실패 — GlobalExceptionHandler에서 503으로 매핑
public class TossApiException extends RuntimeException {
    public TossApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
