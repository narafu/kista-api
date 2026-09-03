package com.kista.application.event;

import java.util.UUID;

// 사용자 승인 성공 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record UserApprovedEvent(UUID userId) {}
