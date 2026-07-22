package com.kista.adapter.out.toss;

import java.time.Duration;
import java.util.Optional;

interface TossTokenStore {

    // scope의 현재 canonical token 조회 — 없거나 만료됐으면 empty
    Optional<CanonicalToken> find(String scope);

    // scope에 대한 발급 owner lease 획득 시도 — 이미 점유 중이면 empty
    Optional<Lease> tryAcquire(String scope, String ownerId, Duration leaseTtl);

    // lease가 여전히 최신 세대일 때만 canonical token 저장(CAS) — stale이면 STALE 반환
    StoreResult storeIfCurrent(Lease lease, TokenValue token, Duration canonicalTtl);

    // 거절된 토큰이 최근 발급 지문과 일치하는지 확인 — 전파 지연 중 401 오탐 방지
    boolean matchesRecentFingerprint(String scope, String token);

    // owner id가 일치할 때만 lease 해제(compare-delete)
    void release(Lease lease);

    // Redis에 저장된 발급 완료 토큰 스냅샷 — access token, fencing 세대, canonical 만료 시각
    record CanonicalToken(String accessToken, long generation, long expiresAtEpochMillis) {}

    // 발급 owner 점유권 — scope·owner id·이 lease가 획득한 fencing 세대
    record Lease(String scope, String ownerId, long generation) {}

    // OAuth로 새로 발급된 토큰 원시 값 — access token과 초 단위 만료
    record TokenValue(String accessToken, long expiresInSeconds) {}

    enum StoreResult {
        STORED, // canonical에 정상 저장됨
        STALE // 더 최신 세대가 이미 존재해 저장 거부됨
    }
}
