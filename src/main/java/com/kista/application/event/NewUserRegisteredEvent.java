package com.kista.application.event;

import com.kista.domain.model.user.User;

// 신규 사용자 등록 성공 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record NewUserRegisteredEvent(User user) {}
