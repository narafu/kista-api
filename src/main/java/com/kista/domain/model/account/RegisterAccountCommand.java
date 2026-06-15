package com.kista.domain.model.account;

public record RegisterAccountCommand(
        String nickname,
        String accountNo,       // 평문 (서비스에서 암호화)
        String appKey,       // 평문
        String secretKey,    // 평문
        String brokerAccountCode,  // KIS: null(accountNo에 통합), TOSS: AccountService가 채움
        Account.Broker broker   // null이면 서비스에서 KIS 기본값 적용
) {}
