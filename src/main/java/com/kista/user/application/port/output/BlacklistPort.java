package com.kista.user.application.port.output;

import com.kista.platform.security.TokenBlacklistPort;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// 읽기 3종(isBlacklisted/isJtiBlacklisted/roleChangedAt)은 TokenBlacklistPort(JwtAuthFilter 전용)를
// 그대로 상속 — 쓰기(add/addJti/markRoleChanged)는 로그아웃·강퇴 흐름 전용으로 이 포트가 추가한다.
public interface BlacklistPort extends TokenBlacklistPort {
    void add(UUID userId, Duration ttl); // AT TTL과 동일 기간 차단

    void addJti(String jti, Duration ttl); // 단일 AT의 jti를 TTL 동안 블랙리스트에 등록

    void markRoleChanged(UUID userId, Instant changedAt, Duration ttl); // role 변경 시각 기록 — 이전 발급 AT 무효화용
}
