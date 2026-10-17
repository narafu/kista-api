package com.kista.tradingcore.broker.domain.model;

// 증권사 자격증명(appKey/secretKey)이 유효하지 않을 때 — GlobalExceptionHandler 422 매핑
// account.Account.InvalidBrokerKeyException 복제(순환 방지) — broker는 이 예외를 자체 소유한다
public class BrokerCredentialException extends RuntimeException {
    public BrokerCredentialException() {
        super("증권사 API 키가 유효하지 않습니다.");
    }

    // 세부 사유 서브클래스용 — detail은 사용자 노출 한국어 문구
    protected BrokerCredentialException(String message) {
        super(message);
    }
}
