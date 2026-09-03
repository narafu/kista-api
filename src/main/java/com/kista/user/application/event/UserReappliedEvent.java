package com.kista.user.application.event;

import java.util.UUID;

// 사용자 재신청 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record UserReappliedEvent(UUID userId) {}
