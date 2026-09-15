package com.kista.admin.domain.model;

// broker.domain.model.BrokerRateLimitException own-type — 429 응답 표지 예외
public class AdminBrokerRateLimitException extends RuntimeException {
    public AdminBrokerRateLimitException() {
        super("KIS API 호출 한도를 초과했습니다. 잠시 후 다시 시도하세요");
    }
}
