package com.kista.broker.domain.model.kis;

import com.kista.broker.domain.model.BrokerApiException;
import com.kista.sharedkernel.Broker;

// KIS 외부 API 호출 실패를 나타내는 도메인 예외 — BrokerApiException으로 503 매핑됨
public class KisApiException extends BrokerApiException {
    public KisApiException(String message, Throwable cause) {
        super(Broker.KIS, "KIS", message, cause, Conflict.NONE);
    }
}
