package com.kista.domain.model;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,                    // 카카오 OAuth UID (앱에서 할당)
        String kakaoId,             // 카카오 고유 ID
        String nickname,            // 카카오 닉네임
        UserStatus status,          // 계정 상태
        String telegramBotToken,    // 전체 계좌 텔레그램 봇 토큰 (AES-256 암호화 저장, null 가능)
        String telegramChatId,      // 전체 계좌 텔레그램 Chat ID (null 가능)
        Instant createdAt,
        Instant updatedAt,
        Instant lastReappliedAt     // nullable — 마지막 reapply()/reject() 호출 시점 (쿨다운 기준)
) {}
