package com.kista.domain.model.account;

public record RegisterAccountCommand(
        String nickname,
        String accountNo,      // 평문 (서비스에서 암호화)
        String kisAppKey,      // 평문
        String kisSecretKey,   // 평문
        String kisAccountType  // 기본값 "01"
) {}
