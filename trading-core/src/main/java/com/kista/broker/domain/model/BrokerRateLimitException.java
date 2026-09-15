package com.kista.broker.domain.model;

// KIS EGW00133 — 1분당 토큰 발급 1회 제한 초과
// account.Account.KisRateLimitException 복제(순환 방지) — broker는 이 예외를 자체 소유한다
public class BrokerRateLimitException extends RuntimeException {
    public BrokerRateLimitException() {
        super("KIS API 호출 한도를 초과했습니다. 잠시 후 다시 시도하세요");
    }
}
