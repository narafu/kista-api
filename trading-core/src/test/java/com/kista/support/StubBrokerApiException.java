package com.kista.support;

import com.kista.broker.domain.model.BrokerApiException;
import com.kista.sharedkernel.Broker;

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
        super(Broker.MOCK, vendorLabel, message, null, conflict);
    }
}
