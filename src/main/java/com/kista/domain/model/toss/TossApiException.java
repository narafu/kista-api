package com.kista.domain.model.toss;

// Toss 외부 API 호출 실패 — GlobalExceptionHandler에서 503으로 매핑
public class TossApiException extends RuntimeException {

    // 취소 요청 직전/직후 체결이 확정되어 409 CONFLICT(already-filled)로 거부된 경우 — 예상된 경합, 어댑터가 판정해 전달
    private final boolean alreadyFilledConflict;

    public TossApiException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public TossApiException(String message, Throwable cause, boolean alreadyFilledConflict) {
        super(message, cause);
        this.alreadyFilledConflict = alreadyFilledConflict;
    }

    public boolean isAlreadyFilledConflict() {
        return alreadyFilledConflict;
    }
}
