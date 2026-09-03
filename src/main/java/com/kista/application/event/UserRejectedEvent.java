package com.kista.application.event;

import java.util.UUID;

// 사용자 거절 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record UserRejectedEvent(UUID userId) {}
