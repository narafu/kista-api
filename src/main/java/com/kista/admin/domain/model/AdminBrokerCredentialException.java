package com.kista.admin.domain.model;

// broker.domain.model.BrokerCredentialException own-type — TradingCommandHttpAdapter가 내부
// API 422 응답을 원래 예외 타입으로 되돌리기 위해 admin 쪽에서 생성·포착하는 표지 예외
public class AdminBrokerCredentialException extends RuntimeException {
    public AdminBrokerCredentialException() {
        super("증권사 API 키가 유효하지 않습니다");
    }
}
