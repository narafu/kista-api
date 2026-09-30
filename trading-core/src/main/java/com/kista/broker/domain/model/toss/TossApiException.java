package com.kista.broker.domain.model.toss;

import com.kista.broker.domain.model.BrokerApiException;
import com.kista.sharedkernel.Broker;

// Toss 외부 API 호출 실패 — BrokerApiException으로 503 매핑됨
public class TossApiException extends BrokerApiException {

    public TossApiException(String message, Throwable cause) {
        this(message, cause, Conflict.NONE);
    }

    public TossApiException(String message, Throwable cause, Conflict conflict) {
        super(Broker.TOSS, "Toss", message, cause, conflict);
    }
}
