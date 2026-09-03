package com.kista.user.application.event;

import java.util.UUID;

// 사용자 cascade 삭제 완료 — 트랜잭션 커밋 후에만 발행됨 (관리자 알림용)
public record UserDeletedEvent(UUID userId) {}
