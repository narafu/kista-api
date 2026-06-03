package com.kista.domain.model.kis;

// KIS 외부 API 호출 실패를 나타내는 도메인 예외 — 컨트롤러에서 503으로 매핑됨
public class KisApiException extends RuntimeException {
    public KisApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
