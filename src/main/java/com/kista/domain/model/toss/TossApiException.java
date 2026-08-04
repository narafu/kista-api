package com.kista.domain.model.toss;

// Toss 외부 API 호출 실패 — GlobalExceptionHandler에서 503으로 매핑
public class TossApiException extends RuntimeException {

    // 409 CONFLICT 세부 사유 — 어댑터(TossHttpClient)가 응답 바디를 판정해 전달하는 예상된 경합
    public enum Conflict {
        NONE,
        ALREADY_FILLED,   // 취소 요청 직전/직후 체결이 확정된 경우
        ALREADY_CANCELED, // 이미 취소 처리된 주문에 중복 취소 요청이 도착한 경우
    }

    private final Conflict conflict;

    public TossApiException(String message, Throwable cause) {
        this(message, cause, Conflict.NONE);
    }

    public TossApiException(String message, Throwable cause, Conflict conflict) {
        super(message, cause);
        this.conflict = conflict;
    }

    public boolean isAlreadyFilledConflict() {
        return conflict == Conflict.ALREADY_FILLED;
    }

    public boolean isAlreadyCanceledConflict() {
        return conflict == Conflict.ALREADY_CANCELED;
    }
}
