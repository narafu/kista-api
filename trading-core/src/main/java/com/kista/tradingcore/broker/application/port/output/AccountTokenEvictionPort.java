package com.kista.tradingcore.broker.application.port.output;

import java.util.Collection;
import java.util.UUID;

// 계좌 키 교체·계좌 삭제·탈퇴 시 증권사 무관하게 계좌 토큰 캐시를 제거하는 포트 — KIS DB(broker_tokens)와 Toss Redis canonical을 함께 지운다
public interface AccountTokenEvictionPort {
    // KIS 토큰 하드 삭제는 호출자 트랜잭션에 합류, Toss Redis 무효화는 호출자 트랜잭션이 있으면 커밋 뒤(없으면 즉시) 실행하며 실패해도 warn 로그 후 진행(TTL로 자연 만료)
    void evict(Collection<UUID> accountIds);
}
