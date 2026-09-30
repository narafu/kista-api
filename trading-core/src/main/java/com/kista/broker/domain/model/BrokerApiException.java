package com.kista.broker.domain.model;

import com.kista.sharedkernel.Broker;

// 증권사 외부 API 호출 실패의 벤더 중립 상위 타입 — 소비자(trading 등)는 KIS/Toss 구체 예외를 몰라도 이 타입 하나로 catch한다(503 매핑)
public abstract class BrokerApiException extends RuntimeException {

    // 409 CONFLICT 세부 사유 — 어댑터가 응답 바디를 판정해 전달하는 예상된 경합(현재 Toss만 판정)
    public enum Conflict {
        NONE,
        ALREADY_FILLED,   // 취소 요청 직전/직후 체결이 확정된 경우
        ALREADY_CANCELED, // 이미 취소 처리된 주문에 중복 취소 요청이 도착한 경우
    }

    private final Broker vendor;      // 예외를 낸 증권사
    private final String vendorLabel; // 응답 title·로그용 벤더 표기("KIS"/"Toss")
    private final Conflict conflict;  // 409 세부 사유(없으면 NONE)

    protected BrokerApiException(Broker vendor, String vendorLabel, String message, Throwable cause, Conflict conflict) {
        super(message, cause);
        this.vendor = vendor;
        this.vendorLabel = vendorLabel;
        this.conflict = conflict;
    }

    public Broker vendor() {
        return vendor;
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
