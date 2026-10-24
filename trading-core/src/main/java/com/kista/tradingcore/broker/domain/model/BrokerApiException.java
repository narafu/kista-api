package com.kista.tradingcore.broker.domain.model;

// 증권사 외부 API 호출 실패의 벤더 중립 상위 타입 — 소비자(trading 등)는 KIS/Toss 구체 예외를 몰라도 이 타입 하나로 catch한다(503 매핑)
public abstract class BrokerApiException extends RuntimeException {

    // 409 CONFLICT 세부 사유 — 어댑터가 응답 바디를 판정해 전달하는 예상된 경합(현재 Toss만 판정)
    public enum Conflict {
        NONE,
        ALREADY_FILLED,   // 취소 요청 직전/직후 체결이 확정된 경우
        ALREADY_CANCELED, // 이미 취소 처리된 주문에 중복 취소 요청이 도착한 경우
    }

    private final String vendorLabel; // 응답 title·로그용 벤더 표기("KIS"/"Toss")
    private final Conflict conflict;  // 409 세부 사유(없으면 NONE)
    private final boolean rejected;   // 증권사가 요청을 접수하지 않았음이 확실(송신 전 실패·명시 거부) — false면 결과 불명
    private final boolean insufficientFunds; // 확정 거절 중 어댑터가 잔고·가능수량 부족 코드로 식별한 경우(isRejected의 하위 분류)

    protected BrokerApiException(String vendorLabel, String message, Throwable cause, Conflict conflict) {
        this(vendorLabel, message, cause, conflict, false);
    }

    protected BrokerApiException(String vendorLabel, String message, Throwable cause, Conflict conflict, boolean rejected) {
        this(vendorLabel, message, cause, conflict, rejected, false);
    }

    protected BrokerApiException(String vendorLabel, String message, Throwable cause, Conflict conflict, boolean rejected,
                                 boolean insufficientFunds) {
        super(message, cause);
        this.insufficientFunds = insufficientFunds;
        this.vendorLabel = vendorLabel;
        this.conflict = conflict;
        this.rejected = rejected;
    }

    // 주문 접수 실패의 확정 거절 판정 SSOT — 자격증명·토큰 한도 예외는 토큰 발급 단계(송신 전)라 타입으로 확정 거절
    public static boolean isRejection(Throwable e) {
        return (e instanceof BrokerApiException b && b.rejected)
                || e instanceof BrokerCredentialException
                || e instanceof BrokerRateLimitException;
    }

    // 잔고 부족 확정 거절 판정 — 방향(BUY/SELL) 판단은 호출자 몫
    public static boolean isInsufficientFundsRejection(Throwable e) {
        return e instanceof BrokerApiException b && b.rejected && b.insufficientFunds;
    }

    public boolean isInsufficientFunds() {
        return rejected && insufficientFunds;
    }

    public boolean isRejected() {
        return rejected;
    }

    public String vendorLabel() {
        return vendorLabel;
    }

    public boolean isAlreadyFilledConflict() {
        return conflict == Conflict.ALREADY_FILLED;
    }

    public boolean isAlreadyCanceledConflict() {
        return conflict == Conflict.ALREADY_CANCELED;
    }
}
