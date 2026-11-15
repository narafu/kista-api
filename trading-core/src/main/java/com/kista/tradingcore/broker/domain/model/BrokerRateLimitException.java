package com.kista.tradingcore.broker.domain.model;

// 브로커 토큰 발급 횟수 제한 초과
// account 쪽 동일 예외 복제(순환 방지) — broker는 이 예외를 자체 소유한다
public class BrokerRateLimitException extends RuntimeException {
    public BrokerRateLimitException() {
        super("증권사 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요.");
    }
}
