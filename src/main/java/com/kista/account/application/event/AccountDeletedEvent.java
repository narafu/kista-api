package com.kista.account.application.event;

import java.util.UUID;

// 계좌 cascade 삭제 완료 — 트랜잭션 커밋 후에만 발행됨
public record AccountDeletedEvent(UUID accountId) {}
