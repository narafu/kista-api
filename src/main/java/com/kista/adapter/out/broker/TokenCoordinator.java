package com.kista.adapter.out.broker;

import java.util.UUID;

// 계좌 단위 브로커 access token 발급/복구를 조정하는 공통 계약.
// 중복 발급 방지 메커니즘은 구현체마다 다르다 — KIS(KisTokenCoordinator)는 JVM-local 락 +
// PostgreSQL broker_tokens 캐시, Toss(TossDistributedTokenCoordinator)는 Redis 분산 lease +
// fencing generation CAS. 이 인터페이스는 "발급/복구" 연산의 형태만 통일한다 — 재시도 횟수·
// 백오프 여부는 각 브로커의 HttpClient가 결정하며 freshlyIssued는 그 판단을 위한 신호일 뿐
// 구현체에 특정 정책을 강제하지 않는다. application/domain 레이어를 넘나들지 않는 순수
// adapter 내부 계약이라 domain/port/out에 두지 않는다(ArchUnit *Port 접미사 규칙 대상 아님).
public interface TokenCoordinator {

    // 정상 조회 — 캐시 히트 시 즉시 반환, 미스 시 issuer로 신규 발급 후 캐시에 저장
    String obtain(UUID accountId, TokenIssuer issuer);

    // 401 복구 — rejectedToken을 무효화하고 최신/신규 토큰을 반환한다
    RecoveredToken recover(UUID accountId, String rejectedToken, TokenIssuer issuer);

    @FunctionalInterface
    interface TokenIssuer {
        IssuedToken issue();
    }

    // OAuth로 갓 발급된 토큰 — expiresInSeconds는 OAuth 응답의 상대 만료 시간
    record IssuedToken(String accessToken, long expiresInSeconds) {}

    // 401 복구 결과 — freshlyIssued=false는 이미 캐시나 다른 인스턴스가 저장해 둔 토큰을 재사용한
    // 경우(전파 지연 위험 없음), true는 방금 발급되었거나 그에 준하는 경우(호출부가 백오프를 고려할 수 있음)
    record RecoveredToken(String accessToken, boolean freshlyIssued) {}
}
