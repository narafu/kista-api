package com.kista.tradingcore.account.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

public record UpdateAccountCommand(
        String nickname,
        @Nullable LocalDate apiKeyExpiresOn // 사용자 입력 만료일(KST) — null이면 추정·유지
) {}
