package com.kista.tradingcore.support;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// broker 밖 테스트용 벤더 중립 BrokerApiException — KisApiException/TossApiException 등 벤더 타입을 테스트에 들이지 않기 위한 최소 서브클래스
public class StubBrokerApiException extends BrokerApiException {

    // 벤더 표기 미지정 — 응답 title이 필요 없는 테스트용
    public StubBrokerApiException(String message) {
        this("Stub", message, Conflict.NONE);
    }

    // 409 세부 사유 지정 — 이미 취소/체결 경합 시나리오용
    public StubBrokerApiException(String message, Conflict conflict) {
        this("Stub", message, conflict);
    }

    // 벤더 표기 지정 — 예외 핸들러 title("<vendorLabel> API Error") 검증용
    public StubBrokerApiException(String vendorLabel, String message, Conflict conflict) {
        super(vendorLabel, message, null, conflict);
    }

    private StubBrokerApiException(String message, boolean rejected) {
        super("Stub", message, null, Conflict.NONE, rejected);
    }

    private StubBrokerApiException(String message, boolean rejected, boolean insufficientFunds) {
        super("Stub", message, null, Conflict.NONE, rejected, insufficientFunds);
    }

    // 확정 거절 중 잔고 부족 코드로 식별된 실패 — BUY 잔고 부족 분류 시나리오용
    public static StubBrokerApiException insufficientFunds(String message) {
        return new StubBrokerApiException(message, true, true);
    }

    // 증권사가 접수하지 않았음이 확실한 실패(확정 거절) — 주문 접수 분류 시나리오용
    public static StubBrokerApiException rejected(String message) {
        return new StubBrokerApiException(message, true);
    }
}
