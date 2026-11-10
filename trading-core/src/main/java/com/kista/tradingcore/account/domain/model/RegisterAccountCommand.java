package com.kista.tradingcore.account.domain.model;

import com.kista.shared.kernel.Broker;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

public record RegisterAccountCommand(
        String nickname,
        String accountNo,       // 평문 (서비스에서 암호화)
        String appKey,       // 평문
        String secretKey,    // 평문
        Broker broker,   // null이면 서비스에서 KIS 기본값 적용
        @Nullable LocalDate apiKeyExpiresOn // 사용자 입력 만료일(KST) — null이면 추정·유지
) {}
