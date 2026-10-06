package com.kista.user.application.event;

import java.util.UUID;

// ADMIN_KAKAO_IDS seed로 로그인 시 ADMIN 자동 승격 이벤트 — 트랜잭션 커밋 후 admin이 감사 로그로 남긴다
public record UserSeedPromotedEvent(UUID userId) {}
